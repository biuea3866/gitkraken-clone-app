#!/usr/bin/env python3
"""오케스트레이션 output_schema 가 벤더의 strict 구조화 출력 규칙을 지키는지 검사한다.

벤더(codex/OpenAI structured output)는 모든 object 에 대해
`required` 가 `properties` 의 **모든 키**를 담기를 요구한다. 선택 필드를 두면
런타임에 400 invalid_json_schema 로 노드가 전멸한다 — 그 실패는 dry-run 으로
드러나지 않고 유료 호출 뒤에야 보이므로 여기서 미리 잡는다.
"""
import json
import pathlib
import sys

SCHEMA_DIR = pathlib.Path(__file__).resolve().parents[1] / "orchestration" / "schemas"


def walk(node, path, problems):
    if not isinstance(node, dict):
        return
    if node.get("type") == "object" and "properties" in node:
        props = list(node["properties"])
        required = node.get("required")
        if required is None:
            problems.append(f"{path}: required 없음 (properties {len(props)}개)")
        else:
            missing = [k for k in props if k not in required]
            if missing:
                problems.append(f"{path}: required 에 빠진 키 {missing}")
        if node.get("additionalProperties") is not False:
            problems.append(f"{path}: additionalProperties 가 false 가 아니다")
    for key in ("properties", "items", "$defs", "definitions"):
        child = node.get(key)
        if isinstance(child, dict):
            if key in ("properties", "$defs", "definitions"):
                for name, sub in child.items():
                    walk(sub, f"{path}.{name}", problems)
            else:
                walk(child, f"{path}[]", problems)


def main() -> int:
    failed = False
    for schema in sorted(SCHEMA_DIR.glob("*.json")):
        problems: list[str] = []
        try:
            data = json.loads(schema.read_text())
        except json.JSONDecodeError as error:
            print(f"  ✗ {schema.name}: JSON 파싱 실패 — {error}")
            failed = True
            continue
        walk(data, schema.stem, problems)
        if problems:
            failed = True
            print(f"  ✗ {schema.name}")
            for problem in problems:
                print(f"      {problem}")
        else:
            print(f"  ✓ {schema.name}")
    if failed:
        print("\n스키마가 strict 구조화 출력 규칙을 위반합니다 — 모든 object 의 required 가")
        print("properties 전체를 담아야 하고 additionalProperties 는 false 여야 합니다.")
        print("선택 필드가 필요하면 '없으면 빈 값' 으로 두고 required 에 넣으세요.")
        return 1
    print("\n✓ 전 스키마 strict 규칙 통과")
    return 0


if __name__ == "__main__":
    sys.exit(main())
