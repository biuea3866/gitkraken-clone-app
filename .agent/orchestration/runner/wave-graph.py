#!/usr/bin/env python3
"""티켓 wave 실행기 — 티켓 DAG 를 위상정렬해 wave 단위로 병렬 실행한다.

`run-graph.py` 는 **워크플로우 1개 안의 노드 DAG** 를 돈다. 이 실행기는 그 **위** 층으로,
**티켓 DAG** 를 돌며 wave 마다 러너 프로세스를 전량 동시에 띄운다.

왜 워크플로우 TOML 로는 안 되는가: 노드는 정적(`[[nodes]]`)이고 `cwd` 는 그래프당 하나이며
노드 타입은 LLM·gate 둘뿐이다. 티켓 N건은 가변 실행 단위 + cwd N개다.

의존·소유의 SSOT 는 **티켓 md 헤더**다:
    > wave 3 · 사이즈 M · 의존 UND-06, UND-10 · 소유 `presentation/staging/`
`tickets/README.md` 가 "둘이 어긋나면 티켓 헤더가 정본" 이라고 정해 둔 그 헤더다.
헤더가 없으면 **의존 없음으로 가정하지 않고 중단한다** — 침묵은 근거 없는 실행을 만든다.

서드파티 의존 0 (stdlib only) — `run-graph.py` 와 같은 원칙.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import importlib.util
import json
import pathlib
import re
import shlex
import subprocess
import sys
import time
from datetime import datetime

RUNNER_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = RUNNER_DIR.parents[2]  # runner → orchestration → .agent → 레포 루트
RUN_GRAPH = RUNNER_DIR / "run-graph.py"
TICKETS_DIR = REPO_ROOT / "tickets"
DEFAULT_WORKTREE_ROOT = REPO_ROOT.parent / f"{REPO_ROOT.name}-worktrees"


def fail(message: str) -> None:
    print(f"🛑 {message}", file=sys.stderr)
    sys.exit(1)


# ---------------------------------------------------------------- 티켓 헤더 파싱


def parse_ticket(ticket: str) -> dict:
    """티켓 md 헤더에서 wave · 의존 · 소유를 읽는다.

    헤더 형식(tickets/README.md "티켓 md 규약"):
        > wave 3 · 사이즈 M · 의존 UND-06, UND-10 · 소유 `a/` · `b/`
    """
    matches = sorted(TICKETS_DIR.glob(f"{ticket}-*.md"))
    if not matches:
        fail(f"티켓 파일이 없습니다: tickets/{ticket}-*.md")
    if len(matches) > 1:
        fail(f"{ticket}: 같은 번호의 티켓 파일이 여러 개입니다 — {[m.name for m in matches]}")
    path = matches[0]

    header = next(
        (line for line in path.read_text().split("\n")[:8] if line.startswith("> ")), None
    )
    if not header:
        fail(
            f"{path.name}: 헤더 줄(`> wave … · 의존 … · 소유 …`)이 없습니다.\n"
            "의존을 읽을 수 없으면 병렬 순서를 정할 수 없습니다 — 의존 없음으로 가정하지 않습니다.\n"
            "tickets/README.md '티켓 md 규약' 형식으로 헤더를 추가하세요."
        )

    depends_match = re.search(r"의존\s*(.*?)\s*·\s*소유", header)
    if not depends_match:
        fail(f"{path.name}: 헤더에서 '의존 … · 소유' 구간을 찾지 못했습니다 — {header!r}")
    depends = re.findall(r"UND-\d+", depends_match.group(1))

    owns_match = re.search(r"·\s*소유\s*(.*)$", header)
    owns = []
    if owns_match:
        # 백틱으로 감싼 경로를 우선 취하고, 없으면 ' · ' 로 끊는다.
        owns = re.findall(r"`([^`]+)`", owns_match.group(1))
        if not owns:
            owns = [part.strip() for part in owns_match.group(1).split("·") if part.strip()]

    wave_match = re.search(r"wave\s*(\S+)", header)
    return {
        "ticket": ticket,
        "path": str(path.relative_to(REPO_ROOT)),
        "wave": wave_match.group(1) if wave_match else "?",
        "depends": depends,
        "owns": owns,
    }


# ---------------------------------------------------------------- 티켓 DAG


def scan_wave(wave: str) -> tuple[list[str], list[str]]:
    """헤더의 wave 값이 일치하는 티켓을 찾는다.

    스캔은 **관대하게** 한다 — 헤더가 없는 티켓 때문에 무관한 wave 선택이 막히면 안 된다.
    다만 조용히 넘기지 않고 "선택에서 제외됨" 으로 돌려주어 호출부가 출력하게 한다.
    선택된 티켓은 이후 parse_ticket 이 엄격하게 다시 읽는다.
    """
    selected, skipped = [], []
    for path in sorted(TICKETS_DIR.glob("UND-*.md")):
        match = re.match(r"(UND-\d+)", path.name)
        if not match:
            continue
        header = next(
            (line for line in path.read_text().split("\n")[:8] if line.startswith("> ")), None
        )
        if not header:
            skipped.append(path.name)
            continue
        found = re.search(r"wave\s*(\S+)", header)
        if found and found.group(1) == wave:
            selected.append(match.group(1))
    return selected, skipped


def ticket_waves(tickets: dict[str, dict]) -> tuple[list[list[str]], list[str]]:
    """대상 집합 안에서만 위상정렬한다.

    대상 밖 의존은 **이미 완료된 것으로 간주**한다 (부분 실행 허용). 무엇을 그렇게 간주했는지
    돌려주어 호출부가 출력하게 한다 — 조용히 무시하면 순서가 틀렸는지 알 수 없다.
    """
    external: list[str] = []
    pending = {
        ticket: {dep for dep in info["depends"] if dep in tickets}
        for ticket, info in tickets.items()
    }
    for ticket, info in tickets.items():
        for dep in info["depends"]:
            if dep not in tickets:
                external.append(f"{ticket} → {dep}")

    waves: list[list[str]] = []
    while pending:
        ready = sorted(t for t, deps in pending.items() if not deps)
        if not ready:
            fail(
                "티켓 의존에 순환이 있습니다 (남은 티켓): "
                + ", ".join(f"{t}←{sorted(d)}" for t, d in sorted(pending.items()))
            )
        waves.append(ready)
        for ticket in ready:
            del pending[ticket]
        for deps in pending.values():
            deps -= set(ready)
    return waves, sorted(set(external))


def check_overlap(wave: list[str], tickets: dict[str, dict]) -> list[str]:
    """같은 wave 안의 소유 경로 교집합을 찾는다 (Rule 3 — 한 파일 = 한 티켓)."""
    conflicts = []
    for index, left in enumerate(wave):
        for right in wave[index + 1 :]:
            shared = set(tickets[left]["owns"]) & set(tickets[right]["owns"])
            if shared:
                conflicts.append(f"{left} ↔ {right}: {sorted(shared)}")
    return conflicts


# ---------------------------------------------------------------- 워크플로우 성격


def workflow_writes(workflow: pathlib.Path) -> bool:
    """이 워크플로우에 파일을 쓰는 노드가 있는가.

    프로필 해소는 `run-graph.py` 의 함수를 그대로 재사용한다 — 로직을 복제하면 두 곳이 어긋난다.
    """
    spec = importlib.util.spec_from_file_location("run_graph", RUN_GRAPH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    adapters = module.load_adapters()
    profiles = module.load_profiles(adapters)
    graph = module.load_graph(workflow, adapters, profiles)
    for node in graph["nodes"]:
        if module.is_gate(node):
            continue
        if node.get("permission_mode") == "acceptEdits":
            return True
        if node.get("sandbox") == "workspace-write":
            return True
    return False


# ---------------------------------------------------------------- 워크트리


def ensure_worktree(ticket: str, root: pathlib.Path, branch_prefix: str) -> pathlib.Path:
    """티켓 전용 워크트리를 확보한다. 이미 있으면 재사용한다.

    `git worktree remove` 는 호출하지 않는다 — 사람의 미커밋 변경을 지울 수 있다.
    """
    path = root / ticket.lower()
    if path.is_dir():
        return path
    root.mkdir(parents=True, exist_ok=True)
    branch = f"{branch_prefix}/{ticket}"
    subprocess.run(["git", "fetch", "origin"], cwd=REPO_ROOT, check=False, capture_output=True)
    existing = subprocess.run(
        ["git", "rev-parse", "--verify", branch], cwd=REPO_ROOT, capture_output=True
    )
    command = (
        ["git", "worktree", "add", str(path), branch]
        if existing.returncode == 0
        else ["git", "worktree", "add", str(path), "-b", branch, "origin/main"]
    )
    done = subprocess.run(command, cwd=REPO_ROOT, capture_output=True, text=True)
    if done.returncode != 0:
        fail(
            f"{ticket}: 워크트리 생성 실패 — {shlex.join(command)}\n"
            f"{done.stdout}\n{done.stderr}"
        )
    return path


# ---------------------------------------------------------------- 러너 실행


def run_ticket(
    ticket: str, workflow: pathlib.Path, cwd: pathlib.Path, args: argparse.Namespace
) -> dict:
    """티켓 하나에 대해 run-graph.py 프로세스를 하나 띄운다."""
    runner = cwd / RUN_GRAPH.relative_to(REPO_ROOT)
    if not runner.is_file():
        fail(f"{ticket}: 워크트리에 러너가 없습니다 — {runner}")
    run_dir = cwd / ".agent/orchestration/runs" / ticket

    command = [
        str(runner),
        str(cwd / workflow.relative_to(REPO_ROOT)),
        "--run-dir",
        str(run_dir),
        "--set",
        f"ticket={ticket}",
        "--max-parallel",
        str(args.max_parallel),
    ]
    for assignment in args.set:
        command += ["--set", assignment]
    for assignment in args.set_each:
        command += ["--set", assignment.replace("{ticket}", ticket)]
    if args.dry_run:
        command.append("--dry-run")

    started = time.monotonic()
    started_wall = time.time()
    log_path = run_dir / "wave-runner.log"
    run_dir.mkdir(parents=True, exist_ok=True)
    done = subprocess.run(command, cwd=cwd, capture_output=True, text=True)
    log_path.write_text(
        f"command: {shlex.join(command)}\ncwd: {cwd}\n\n"
        f"--- stdout ---\n{done.stdout}\n--- stderr ---\n{done.stderr}\n"
    )

    manifest = run_dir / "run.json"
    summary = {}
    # **이번 실행이 쓴 매니페스트만 믿는다.** run-dir 은 티켓별로 재사용되므로, 이전 라운드의
    # run.json 을 읽으면 이번 결과가 아닌 것을 이번 결과로 보고한다 (--dry-run 은 아예 쓰지 않는다).
    fresh = manifest.is_file() and manifest.stat().st_mtime >= started_wall - 1
    if manifest.is_file() and not fresh:
        summary = {"manifest": "이번 실행이 쓰지 않음 (이전 라운드 파일 무시)"}
    if fresh:
        try:
            data = json.loads(manifest.read_text())
            summary = {
                "halted_at_gate": data.get("halted_at_gate"),
                "total_cost_usd": data.get("total_cost_usd"),
                "failed_nodes": [
                    node["id"] for node in data.get("nodes", []) if node.get("status") != "ok"
                ],
            }
        except json.JSONDecodeError:
            summary = {"manifest_parse_error": True}

    return {
        "ticket": ticket,
        "exit_code": done.returncode,
        "duration_s": round(time.monotonic() - started, 1),
        "worktree": str(cwd),
        "run_dir": str(run_dir),
        "log": str(log_path),
        **summary,
    }


# ---------------------------------------------------------------- main


def main() -> int:
    parser = argparse.ArgumentParser(
        description="티켓 DAG 를 위상정렬해 wave 단위로 run-graph.py 를 병렬 실행한다."
    )
    parser.add_argument("workflow", type=pathlib.Path, help="workflows/*.toml 경로")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--tickets", help="대상 티켓 (쉼표 구분) — 예: UND-13,UND-14,UND-16")
    group.add_argument("--wave", help="티켓 헤더의 wave 값이 이것인 티켓 전량 — 예: 3")
    parser.add_argument(
        "--set", action="append", default=[], metavar="KEY=VALUE", help="전 티켓 공통 자리표시자"
    )
    parser.add_argument(
        "--set-each",
        action="append",
        default=[],
        metavar="KEY=VALUE",
        help="티켓별 자리표시자 — 값의 {ticket} 이 티켓 키로 치환된다",
    )
    parser.add_argument("--max-parallel", type=int, default=6, help="각 러너 내부 wave 폭 (기본 6)")
    parser.add_argument(
        "--max-tickets", type=int, default=0, help="동시 티켓 상한 (기본 0 = 전량 — 배치 금지)"
    )
    parser.add_argument("--worktree-root", type=pathlib.Path, default=DEFAULT_WORKTREE_ROOT)
    parser.add_argument("--branch-prefix", default="feat")
    parser.add_argument(
        "--dry-run", action="store_true", help="각 러너에 --dry-run 전달 (LLM 호출 0)"
    )
    parser.add_argument(
        "--allow-overlap", action="store_true", help="같은 wave 의 소유 경로 교집합 차단을 해제한다"
    )
    parser.add_argument(
        "--no-worktree",
        action="store_true",
        help="워크트리를 만들지 않고 현재 트리에서 실행 (쓰기 워크플로우면 거부)",
    )
    parser.add_argument("--plan-only", action="store_true", help="티켓 wave 계획만 출력하고 종료")
    args = parser.parse_args()

    if not args.workflow.is_file():
        fail(f"워크플로우 파일이 없습니다: {args.workflow}")
    # 상대 경로로 받아도 워크트리별 경로로 다시 붙일 수 있게 절대 경로로 고정한다.
    args.workflow = args.workflow.resolve()
    try:
        args.workflow.relative_to(REPO_ROOT)
    except ValueError:
        fail(f"워크플로우가 레포 밖에 있습니다: {args.workflow}")

    # 대상 티켓 결정
    if args.tickets:
        names = [value.strip() for value in args.tickets.split(",") if value.strip()]
    else:
        names, skipped = scan_wave(args.wave)
        if skipped:
            print(f"⚠️  헤더가 없어 wave 선택에서 제외된 티켓 {len(skipped)}개: {', '.join(skipped)}")
        if not names:
            fail(f"wave '{args.wave}' 에 해당하는 티켓이 없습니다.")

    tickets = {name: parse_ticket(name) for name in names}
    waves, external = ticket_waves(tickets)

    writes = workflow_writes(args.workflow)
    if args.no_worktree and writes:
        fail(
            f"{args.workflow.name} 은 파일을 쓰는 노드가 있어 --no-worktree 로 돌릴 수 없습니다.\n"
            "티켓 N건이 한 트리를 공유하면 서로의 변경을 덮어씁니다 (파일 소유 Rule 3)."
        )

    print(f"워크플로우: {args.workflow.name}  ({'쓰기' if writes else '읽기 전용'})")
    print(f"티켓 {len(tickets)}개  ·  티켓 wave {len(waves)}개")
    for index, wave in enumerate(waves, start=1):
        print(f"  wave {index} (너비 {len(wave)}): {', '.join(wave)}")
    if external:
        print("  대상 밖 의존 — 이미 완료로 간주:")
        for edge in external:
            print(f"    {edge}")

    # 소유 교집합 — 실행 전에 전 wave 를 검사한다 (한 wave 만 보고 시작하면 뒤에서 터진다)
    blocked = False
    for index, wave in enumerate(waves, start=1):
        conflicts = check_overlap(wave, tickets)
        if conflicts:
            marker = "⚠️ " if args.allow_overlap else "🛑 "
            print(f"{marker}wave {index}: 소유 경로 교집합 — 병렬 실행이 곧 머지 충돌입니다")
            for conflict in conflicts:
                print(f"    {conflict}")
            blocked = blocked or not args.allow_overlap
    if blocked:
        fail("소유 교집합이 있어 멈췄습니다. 티켓을 다른 wave 로 나누거나 --allow-overlap 을 쓰세요.")

    if args.plan_only:
        return 0

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    results: list[dict] = []
    halted: str | None = None

    for index, wave in enumerate(waves, start=1):
        # 워크트리는 스폰 전에 순차로 확보한다 — git worktree add 는 동시 실행에 안전하지 않다.
        locations = {
            ticket: REPO_ROOT
            if args.no_worktree
            else ensure_worktree(ticket, args.worktree_root, args.branch_prefix)
            for ticket in wave
        }

        workers = len(wave) if args.max_tickets <= 0 else min(args.max_tickets, len(wave))
        print(f"\n▶ 티켓 wave {index} 실행 — {len(wave)}개 동시 (worker {workers})")
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
            futures = {
                pool.submit(run_ticket, ticket, args.workflow, locations[ticket], args): ticket
                for ticket in wave
            }
            for future in concurrent.futures.as_completed(futures):
                result = future.result()
                results.append(result)
                mark = {0: "✅", 2: "🚦"}.get(result["exit_code"], "❌")
                gate = f" gate={result['halted_at_gate']}" if result.get("halted_at_gate") else ""
                cost = (
                    f" ${result['total_cost_usd']:.4f}"
                    if result.get("total_cost_usd")
                    else ""
                )
                print(
                    f"  {mark} {result['ticket']} — exit {result['exit_code']}"
                    f" ({result['duration_s']}s{cost}){gate}"
                )

        wave_results = [r for r in results if r["ticket"] in wave]
        if any(r["exit_code"] == 2 for r in wave_results):
            halted = f"wave {index} — 게이트 도달"
            break
        if any(r["exit_code"] != 0 for r in wave_results):
            halted = f"wave {index} — 실패"
            break

    manifest_path = REPO_ROOT / ".agent/orchestration/runs" / f"wave-{stamp}.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(
        json.dumps(
            {
                "workflow": str(args.workflow),
                "dry_run": args.dry_run,
                "ticket_waves": waves,
                "external_dependencies": external,
                "halted": halted,
                "tickets": results,
            },
            ensure_ascii=False,
            indent=2,
        )
    )

    ok = sum(1 for r in results if r["exit_code"] == 0)
    gates = [r["ticket"] for r in results if r["exit_code"] == 2]
    print(f"\n티켓 {ok}/{len(results)} 완료 · 게이트 대기 {len(gates)}개")
    print(f"매니페스트: {manifest_path}")
    if halted:
        print(f"멈춤: {halted}")
        if gates:
            print("게이트를 wave 단위로 묶어 한 번에 검토하고, 각 워크트리에서 재개하세요:")
            for result in results:
                if result["exit_code"] == 2:
                    print(f"  {result['ticket']}: {result['run_dir']}")
        return 2 if gates else 1
    return 0 if ok == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
