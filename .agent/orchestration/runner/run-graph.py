#!/usr/bin/env python3
"""벤더 중립 워크플로우 러너 (cli 백엔드).

노드 1개 = CLI 1회 실행. 노드 간 전달은 파일로만 한다 (stdout 파싱 의존 금지).
벤더는 `runner/adapters/*.toml` 이 선언한다 — 이 코드에는 벤더 분기가 없다.
새 LLM 에이전트를 붙이려면 어댑터 TOML 1개만 추가한다 (adapters/README.md).

사용법:
    .agent/orchestration/runner/run-graph.py .agent/orchestration/workflows/harness-audit.toml
    ... <workflow.toml> --dry-run
    ... <workflow.toml> --only inventory,synthesize
    ... <workflow.toml> --run-dir <이전 실행> --start-at implement_1   # 게이트 이후 재개
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import pathlib
import re
import shlex
import subprocess
import sys
import time
import tomllib
from datetime import datetime

RUNNER_DIR = pathlib.Path(__file__).resolve().parent
ADAPTER_DIR = RUNNER_DIR / "adapters"
REPO_ROOT = RUNNER_DIR.parents[2]  # runner → orchestration → .agent → 레포 루트
DEFAULT_TIMEOUT_SECONDS = 900

# 러너가 직접 해석하는 노드 키. 그 외 키는 어댑터 [flags] 에 있어야 한다.
RESERVED_NODE_KEYS = {
    "id", "vendor", "type", "needs", "prompt", "role_file", "cwd", "timeout_seconds", "label",
}


def fail(message: str) -> None:
    print(f"🛑 {message}", file=sys.stderr)
    sys.exit(1)


# ---------------------------------------------------------------- 벤더 어댑터


def load_adapters() -> dict[str, dict]:
    """adapters/*.toml 을 전부 읽어 벤더 레지스트리를 만든다."""
    adapters: dict[str, dict] = {}
    for path in sorted(ADAPTER_DIR.glob("*.toml")):
        adapter = tomllib.loads(path.read_text())
        for key in ("id", "base", "prompt_delivery", "result"):
            if key not in adapter:
                fail(f"{path.name}: 필수 키 '{key}' 가 없습니다.")
        if adapter["prompt_delivery"] not in ("stdin", "argv"):
            fail(f"{path.name}: prompt_delivery 는 stdin 또는 argv 여야 합니다.")
        if adapter["result"] not in ("stdout_envelope", "out_file"):
            fail(f"{path.name}: result 는 stdout_envelope 또는 out_file 여야 합니다.")
        adapters[adapter["id"]] = adapter
    if not adapters:
        fail(f"어댑터가 없습니다 — {ADAPTER_DIR}")
    return adapters


def render_fragment(
    template: list[str], value: object, node_id: str, key: str
) -> list[str]:
    """어댑터의 argv 조각 템플릿에 노드 값을 채운다."""
    rendered: list[str] = []
    for piece in template:
        if "{csv}" in piece:
            if not isinstance(value, list):
                fail(f"노드 '{node_id}': {key} 는 리스트여야 합니다.")
            piece = piece.replace("{csv}", ",".join(str(item) for item in value))
        if "{repo_path}" in piece:
            resolved = REPO_ROOT / str(value)
            if not resolved.is_file():
                fail(f"노드 '{node_id}': {key} 파일 없음 — {resolved}")
            piece = piece.replace("{repo_path}", str(resolved))
        if "{value}" in piece:
            piece = piece.replace("{value}", str(value))
        rendered.append(piece)
    return rendered


def build_command(
    node: dict, adapter: dict, out_path: pathlib.Path, cwd: pathlib.Path
) -> list[str]:
    """어댑터 선언만으로 CLI argv 를 조립한다."""
    flags = adapter.get("flags", {})
    runtime = adapter.get("runtime", {})
    command = list(adapter["base"])

    for key, template in runtime.items():
        slot = {"out_file": out_path, "cwd": cwd}.get(key)
        if slot is None:
            fail(f"{adapter['id']} 어댑터: 알 수 없는 runtime 슬롯 '{key}'")
        command += [piece.replace("{path}", str(slot)) for piece in template]

    values = dict(adapter.get("defaults", {}))
    values.update({key: value for key, value in node.items() if key not in RESERVED_NODE_KEYS})

    for key, value in values.items():
        if key not in flags:
            fail(
                f"노드 '{node['id']}': 벤더 '{adapter['id']}' 는 '{key}' 를 지원하지 않습니다"
                f" (지원 키: {sorted(flags)})."
            )
        command += render_fragment(flags[key], value, node["id"], key)

    return command


# ---------------------------------------------------------------- 워크플로우 로드


def load_graph(spec_path: pathlib.Path, adapters: dict[str, dict]) -> dict:
    with spec_path.open("rb") as handle:
        graph = tomllib.load(handle)

    nodes = graph.get("nodes") or []
    if not nodes:
        fail(f"{spec_path}: [[nodes]] 가 비어 있습니다.")

    by_id: dict[str, dict] = {}
    for node in nodes:
        node_id = node.get("id")
        if not node_id:
            fail(f"{spec_path}: id 없는 노드가 있습니다.")
        if node_id in by_id:
            fail(f"{spec_path}: 중복 노드 id '{node_id}'")
        if not node.get("prompt"):
            fail(f"노드 '{node_id}': prompt 가 필요합니다.")
        if is_gate(node):
            if node.get("vendor"):
                fail(f"노드 '{node_id}': gate 노드에는 vendor 를 두지 않습니다 (사람이 판단).")
        elif node.get("vendor") not in adapters:
            fail(
                f"노드 '{node_id}': vendor '{node.get('vendor')}' 어댑터가 없습니다"
                f" (등록된 벤더: {sorted(adapters)})."
            )
        by_id[node_id] = node

    for node in nodes:
        for dependency in node.get("needs", []):
            if dependency not in by_id:
                fail(f"노드 '{node['id']}': 존재하지 않는 의존 '{dependency}'")

    graph["_by_id"] = by_id
    return graph


def is_gate(node: dict) -> bool:
    return node.get("type") == "gate"


def topological_waves(nodes: list[dict]) -> list[list[dict]]:
    """의존이 해소된 노드를 wave 단위로 묶는다. 같은 wave 는 병렬 실행 대상."""
    remaining = {node["id"]: set(node.get("needs", [])) for node in nodes}
    by_id = {node["id"]: node for node in nodes}
    waves: list[list[dict]] = []

    while remaining:
        ready = sorted(node_id for node_id, deps in remaining.items() if not deps)
        if not ready:
            fail(f"의존 순환이 있습니다: {sorted(remaining)}")
        waves.append([by_id[node_id] for node_id in ready])
        for node_id in ready:
            del remaining[node_id]
        for deps in remaining.values():
            deps.difference_update(ready)

    return waves


def descendants_of(start: str, nodes: list[dict]) -> set[str]:
    """start 와 그 후손 전부. --start-at 재개 범위를 정하는 데 쓴다."""
    kept = {start}
    changed = True
    while changed:
        changed = False
        for node in nodes:
            if node["id"] in kept:
                continue
            if kept & set(node.get("needs", [])):
                kept.add(node["id"])
                changed = True
    return kept


# ---------------------------------------------------------------- 프롬프트 조립


def substitute(text: str, variables: dict[str, str], where: str) -> str:
    """프롬프트의 {{key}} 를 --set 값으로 치환한다. 미해결 자리표시자는 실행 전에 실패시킨다."""
    for key, value in variables.items():
        text = text.replace("{{" + key + "}}", value)
    leftover = re.findall(r"\{\{([a-zA-Z_][a-zA-Z0-9_]*)\}\}", text)
    if leftover:
        fail(f"{where}: 치환되지 않은 자리표시자 {sorted(set(leftover))} — --set 으로 넘기세요.")
    return text


def build_prompt(
    node: dict, run_dir: pathlib.Path, upstream: list[str], variables: dict[str, str]
) -> str:
    sections: list[str] = []

    role_file = node.get("role_file")
    if role_file:
        role_path = REPO_ROOT / role_file
        if not role_path.is_file():
            fail(f"노드 '{node['id']}': role_file 없음 — {role_path}")
        sections.append(f"# 역할\n\n{role_path.read_text()}")

    sections.append(
        "# 작업\n\n"
        + substitute(node["prompt"].strip(), variables, f"노드 '{node['id']}' prompt")
    )

    if upstream:
        lines = [
            f"- `{dependency}` → `{run_dir / (dependency + '.json')}`"
            for dependency in upstream
        ]
        sections.append(
            "# 입력 (업스트림 노드 산출물)\n\n"
            "아래 파일을 읽어 근거로 사용하세요. 추측하지 마세요.\n\n" + "\n".join(lines)
        )

    sections.append(
        "# 출력 계약\n\n"
        "최종 응답은 **JSON 객체 하나만** 출력하세요. 산문·코드펜스·설명을 붙이지 마세요."
    )
    return "\n\n".join(sections)


def extract_json_object(text: str) -> dict:
    """모델 응답에서 JSON 객체를 추출한다. 코드펜스·머리말을 허용한다."""
    match = re.search(r"\{.*\}", text, re.S)
    if not match:
        return {"_parse_error": "JSON 객체를 찾지 못했습니다.", "_raw": text[:500]}
    try:
        return json.loads(match.group(0))
    except json.JSONDecodeError as error:
        return {"_parse_error": str(error), "_raw": match.group(0)[:500]}


# ---------------------------------------------------------------- 노드 실행


def run_node(
    node: dict,
    adapter: dict,
    run_dir: pathlib.Path,
    graph_cwd: pathlib.Path,
    variables: dict[str, str],
) -> dict:
    node_id = node["id"]
    out_path = run_dir / f"{node_id}.json"
    log_path = run_dir / f"{node_id}.log"
    prompt = build_prompt(node, run_dir, node.get("needs", []), variables)
    (run_dir / f"{node_id}.prompt.txt").write_text(prompt)

    cwd = REPO_ROOT / node["cwd"] if node.get("cwd") else graph_cwd
    timeout = node.get("timeout_seconds", DEFAULT_TIMEOUT_SECONDS)
    started = time.monotonic()

    command = build_command(node, adapter, out_path, cwd)
    if adapter["prompt_delivery"] == "argv":
        command = command + [prompt]
        stdin_data = ""
    else:
        stdin_data = prompt

    try:
        completed = subprocess.run(
            command,
            input=stdin_data,
            capture_output=True,
            text=True,
            cwd=str(cwd),
            timeout=timeout,
        )
    except FileNotFoundError:
        log_path.write_text(f"CLI 없음: {command[0]}\ncommand: {shlex.join(command)}\n")
        return {
            "id": node_id,
            "vendor": node["vendor"],
            "status": "failed",
            "error": f"CLI 를 찾을 수 없습니다: {command[0]}",
            "duration_s": round(time.monotonic() - started, 1),
        }
    except subprocess.TimeoutExpired:
        log_path.write_text(f"TIMEOUT after {timeout}s\ncommand: {shlex.join(command)}\n")
        return {
            "id": node_id,
            "vendor": node["vendor"],
            "status": "timeout",
            "duration_s": round(time.monotonic() - started, 1),
        }

    duration = round(time.monotonic() - started, 1)
    log_path.write_text(
        f"command: {shlex.join(command)}\n\n--- stdout ---\n{completed.stdout}\n"
        f"--- stderr ---\n{completed.stderr}\n"
    )

    result = {
        "id": node_id,
        "vendor": node["vendor"],
        "model": node.get("model"),
        "effort": node.get("effort"),
        "exit_code": completed.returncode,
        "duration_s": duration,
        "output": str(out_path),
        "log": str(log_path),
    }

    if adapter["result"] == "stdout_envelope":
        envelope = extract_json_object(completed.stdout)
        cost_key = adapter.get("envelope_cost_key")
        if cost_key:
            result["cost_usd"] = envelope.get(cost_key)
        payload = extract_json_object(str(envelope.get(adapter.get("envelope_result_key", "result"), "")))
        out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        # CLI 가 최종 메시지를 파일에 직접 쓴다. 스키마 미지정이면 텍스트일 수 있다.
        if out_path.is_file():
            payload = extract_json_object(out_path.read_text())
        else:
            payload = {"_parse_error": f"{node['vendor']} 가 출력 파일을 쓰지 않았습니다."}
        out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2))

    ok = completed.returncode == 0 and "_parse_error" not in payload
    result["status"] = "ok" if ok else "failed"
    return result


def announce_gate(node: dict, run_dir: pathlib.Path, variables: dict[str, str]) -> dict:
    """사람 게이트. 러너는 대기하지 못하므로 여기서 멈추고 재개 명령을 알려준다."""
    body = substitute(node["prompt"].strip(), variables, f"게이트 '{node['id']}' prompt")
    out_path = run_dir / f"{node['id']}.json"
    out_path.write_text(
        json.dumps(
            {"gate": node["id"], "status": "awaiting_human", "checklist": body},
            ensure_ascii=False,
            indent=2,
        )
    )
    print(f"\n🚦 게이트 '{node['id']}' — 사람이 판단해야 합니다.\n")
    for line in body.splitlines():
        print(f"   {line}")
    return {"id": node["id"], "type": "gate", "status": "awaiting_human", "output": str(out_path)}


# ---------------------------------------------------------------- 드라이버


def main() -> int:
    # 파이프·리다이렉트로 실행해도 wave 진행이 실시간으로 보이게 한다.
    sys.stdout.reconfigure(line_buffering=True)

    parser = argparse.ArgumentParser(description="벤더 중립 워크플로우 러너 (cli 백엔드)")
    parser.add_argument("graph", type=pathlib.Path)
    parser.add_argument("--run-dir", type=pathlib.Path)
    parser.add_argument("--only", help="쉼표로 구분한 노드 id 만 실행 (의존 무시)")
    parser.add_argument(
        "--start-at",
        help="이 노드와 그 후손만 실행한다. 게이트 이후 재개용 — --run-dir 로 이전 실행을 지정해야 한다.",
    )
    parser.add_argument(
        "--set",
        action="append",
        default=[],
        metavar="KEY=VALUE",
        help="프롬프트의 {{KEY}} 를 치환한다 (반복 가능)",
    )
    parser.add_argument("--max-parallel", type=int, default=4)
    parser.add_argument("--dry-run", action="store_true", help="wave 계획만 출력")
    args = parser.parse_args()

    variables: dict[str, str] = {}
    for assignment in args.set:
        if "=" not in assignment:
            fail(f"--set 형식이 잘못됐습니다: {assignment} (KEY=VALUE)")
        key, value = assignment.split("=", 1)
        variables[key.strip()] = value

    adapters = load_adapters()
    graph = load_graph(args.graph, adapters)
    nodes = graph["nodes"]

    if args.only and args.start_at:
        fail("--only 와 --start-at 는 함께 쓸 수 없습니다.")

    if args.only:
        wanted = {value.strip() for value in args.only.split(",")}
        nodes = [node for node in nodes if node["id"] in wanted]
        for node in nodes:
            node["needs"] = [dep for dep in node.get("needs", []) if dep in wanted]

    if args.start_at:
        if args.start_at not in graph["_by_id"]:
            fail(f"--start-at: 존재하지 않는 노드 '{args.start_at}'")
        if not args.run_dir:
            fail("--start-at 은 --run-dir 로 이전 실행 디렉토리를 지정해야 합니다 (업스트림 산출물 필요).")
        kept = descendants_of(args.start_at, nodes)
        nodes = [node for node in nodes if node["id"] in kept]
        # 이미 끝난 업스트림은 스케줄링에서 제외하되, 프롬프트 주입용 needs 는 원본을 남긴다.
        for node in nodes:
            node["_upstream"] = list(node.get("needs", []))
            node["needs"] = [dep for dep in node.get("needs", []) if dep in kept]

    waves = topological_waves(nodes)
    graph_name = graph.get("name", args.graph.stem)

    # 실행 전에 자리표시자를 전수 검증한다 — 노드 실행 중 실패하면 이미 쓴 비용이 날아간다.
    for node in nodes:
        substitute(node["prompt"], variables, f"노드 '{node['id']}' prompt")

    print(f"workflow: {graph_name}  노드 {len(nodes)}개  wave {len(waves)}개")
    for index, wave in enumerate(waves, start=1):
        summary = ", ".join(
            "%s(%s)" % (
                node["id"],
                "게이트/사람" if is_gate(node)
                else f"{node['vendor']}/{node.get('model', '기본')}",
            )
            for node in wave
        )
        print(f"  wave {index} (너비 {len(wave)}): {summary}")
    if args.dry_run:
        return 0

    run_dir = args.run_dir or (
        REPO_ROOT
        / ".agent/orchestration/runs"
        / f"{datetime.now().strftime('%Y%m%d-%H%M%S')}-{graph_name}"
    )
    run_dir.mkdir(parents=True, exist_ok=True)
    graph_cwd = REPO_ROOT / graph.get("cwd", ".")
    print(f"run-dir: {run_dir}\n")

    results: list[dict] = []
    failed: set[str] = set()
    gate_hit: str | None = None

    for index, wave in enumerate(waves, start=1):
        gates = [node for node in wave if is_gate(node)]
        if gates:
            if len(wave) > 1:
                fail(f"wave {index}: 게이트 노드는 단독 wave 여야 합니다 ({[n['id'] for n in wave]}).")
            gate = gates[0]
            if set(gate.get("needs", [])) & failed:
                print(f"  ⏭  {gate['id']} — 업스트림 실패로 게이트에 도달하지 못했습니다")
                results.append({"id": gate["id"], "type": "gate", "status": "skipped"})
                failed.add(gate["id"])
                continue
            results.append(announce_gate(gate, run_dir, variables))
            gate_hit = gate["id"]
            break

        runnable = [node for node in wave if not (set(node.get("needs", [])) & failed)]
        for node in wave:
            if node not in runnable:
                print(f"  ⏭  {node['id']} — 업스트림 실패로 건너뜀")
                results.append({"id": node["id"], "status": "skipped"})
                failed.add(node["id"])

        if not runnable:
            continue

        print(f"▶ wave {index} 실행 (너비 {len(runnable)})")
        workers = min(args.max_parallel, len(runnable))
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
            futures = {
                pool.submit(
                    run_node, node, adapters[node["vendor"]], run_dir, graph_cwd, variables
                ): node
                for node in runnable
            }
            for future in concurrent.futures.as_completed(futures):
                result = future.result()
                results.append(result)
                if result["status"] != "ok":
                    failed.add(result["id"])
                mark = "✅" if result["status"] == "ok" else "❌"
                cost = f" ${result['cost_usd']:.4f}" if result.get("cost_usd") else ""
                print(
                    f"  {mark} {result['id']} — {result['status']}"
                    f" ({result['duration_s']}s{cost})"
                )
        print()

    executed = {result["id"] for result in results}
    pending = [node["id"] for node in nodes if node["id"] not in executed]
    total_cost = sum(result.get("cost_usd") or 0 for result in results)
    manifest = {
        "workflow": graph_name,
        "run_dir": str(run_dir),
        "waves": [[node["id"] for node in wave] for wave in waves],
        "halted_at_gate": gate_hit,
        "pending_nodes": pending,
        "total_cost_usd": round(total_cost, 4),
        "nodes": results,
    }
    (run_dir / "run.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))

    ok_count = sum(1 for result in results if result["status"] == "ok")
    if gate_hit:
        resume_target = pending[0] if pending else "<다음 노드>"
        print(
            f"\n게이트 '{gate_hit}' 에서 멈췄습니다. {ok_count}개 노드 완료,"
            f" 대기 {len(pending)}개, 누적 비용 ${total_cost:.4f}"
        )
        print("검토 후 아래로 재개하세요:")
        print(
            f"  {pathlib.Path(__file__).relative_to(REPO_ROOT)}"
            f" {args.graph} --run-dir {run_dir} --start-at {resume_target}"
        )
        print(f"매니페스트: {run_dir / 'run.json'}")
        return 2

    print(f"완료: {ok_count}/{len(results)} 노드 성공, 누적 비용 ${total_cost:.4f}")
    print(f"매니페스트: {run_dir / 'run.json'}")
    return 0 if ok_count == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
