#!/usr/bin/env bash
# PreToolUse hook (Bash matcher) — env 파일 / 비밀 디렉토리 / 시크릿 환경변수 echo 를 차단.
#
# 설계 (2025-XX rev — F1/F3 fix):
#   기존 allowlist(READ_CMDS) 게이트 제거. 셸 wrapper(bash/zsh/sh/dash/...),
#   인터프리터(-c/-e 본문 재귀), source/dot builtin, eval, command substitution($()/`),
#   괄호 서브쉘 까지 모두 동일 정책으로 잡힌다. allowlist 갱신 누락으로 인한 우회를 막기 위함.
#
# 차단 대상:
#   1) 파일 경로 토큰: .env / .env.* / credentials.json / secrets.{yml,yaml,json}
#                     / id_(rsa|dsa|ecdsa|ed25519|ed448)(._.* 변종 포함)
#                     / *.pem|*.p12|*.pfx|*.keystore|*.jks
#      위 토큰이 어떤 명령의 positional arg 로 등장하든 차단 (cat/grep 같은 명시 read 명령에 한정 X)
#   2) 인터프리터 -c/-e 페이로드: bash/zsh/sh/python/perl/ruby/node/php 본문 안의 비밀 경로
#   3) 환경변수 노출: echo $TOKEN / printenv / env (인자 없이) — 시크릿스러운 변수명
#
# 예외:
#   - .env.example / .env.sample / .env.template 은 git tracked 온보딩 자산이라 허용
#   - 명령에 '# !no-secret-guard' 주석이 포함되면 통과 (감사 로그 유지용)

set -euo pipefail

input=$(cat)
command=$(printf '%s' "$input" | python3 -c "import json,sys;print(json.load(sys.stdin).get('tool_input',{}).get('command',''))" 2>/dev/null || true)

[[ -z "$command" ]] && exit 0

if [[ "$command" == *"# !no-secret-guard"* ]]; then
  echo "ℹ no-secret-guard 주석으로 우회됨 (감사 대상 명령)" >&2
  exit 0
fi

verdict=$(COMMAND="$command" python3 - <<'PY'
import os, re, sys, shlex

cmd = os.environ.get("COMMAND", "")

# SECRET_PATH_TOKEN — 토큰 1개가 통째로 비밀 경로인 경우 매치 (anchored).
# SSH 키 변종: id_(rsa|dsa|ecdsa|ed25519|ed448) + 선택적 [._]<suffix> (.pub/.backup/.old/_personal 등).
SECRET_PATH_TOKEN = re.compile(
    r'^(?:[~A-Za-z0-9_./\-]*/)?'
    r'(?:\.env(?:\.[A-Za-z0-9_.-]+)?'
    r'|credentials\.json'
    r'|secrets?\.(?:ya?ml|json)'
    r'|id_(?:rsa|dsa|ecdsa|ed25519|ed448)(?:[._][A-Za-z0-9._\-]*)?'
    r'|[A-Za-z0-9_.\-]+\.(?:pem|p12|pfx|keystore|jks))$'
)

# 샘플/템플릿 (.env.example / .env.sample / .env.template) 은 git tracked 온보딩 자산이라 허용
SAFE_ENV_TOKEN = re.compile(r'(?:^|/)\.env\.(?:example|sample|template)$')

# Glob 메타 캐릭터를 포함한 토큰은 셸이 .env / *.pem 등으로 확장할 수 있어
# anchored SECRET_PATH_TOKEN 으로는 잡히지 않는다. 별도 substring 검사로 차단.
GLOB_META = re.compile(r'[*?\[]')
SECRET_GLOB_SUBSTR = re.compile(
    r'(?:^|/)\.env'
    r'|(?:^|/)credentials?[.\-_*?\[]'
    r'|(?:^|/)secrets?[.\-_*?\[]'
    r'|(?:^|/)id_(?:rsa|dsa|ecdsa|ed25519|ed448)'
    r'|\.(?:pem|p12|pfx|keystore|jks)(?:$|[^A-Za-z0-9])'
)

# raw 모드용 — 인터프리터 -c/-e 본문 안에서 비밀 경로 substring 을 잡는다.
# shlex 가 python/perl/node 의 quoting 을 일관 파싱 못 하므로 raw regex 가 안전망.
SECRET_PATH_RAW = re.compile(
    r"(?<![A-Za-z0-9_./\-])"
    r"(?:\.env(?:\.[A-Za-z0-9_.\-]+)?"
    r"|credentials\.json"
    r"|secrets?\.(?:ya?ml|json)"
    r"|id_(?:rsa|dsa|ecdsa|ed25519|ed448)(?:[._][A-Za-z0-9._\-]*)?"
    r"|[A-Za-z0-9_.\-]+\.(?:pem|p12|pfx|keystore|jks))"
    r"(?![A-Za-z0-9_./\-])"
)

SECRET_VAR = re.compile(
    r'(?:^|[\s$])\$?\{?([A-Z][A-Z0-9_]*'
    r'(?:TOKEN|KEY|SECRET|PASSWORD|PASSWD|PWD|PAT|API[_-]?KEY|ACCESS[_-]?KEY|PRIVATE[_-]?KEY|CREDENTIAL|AUTH)'
    r'[A-Z0-9_]*)\}?'
)

ECHO_CMDS = {"echo", "printf", "print"}

# 인터프리터별 코드 실행 플래그 — 본문을 재귀 검사한다.
INTERPRETER_FLAGS = {
    "bash":    {"-c", "-lc", "-ic"},
    "sh":      {"-c"},
    "dash":    {"-c"},
    "zsh":     {"-c", "-lc", "-ic"},
    "ksh":     {"-c"},
    "fish":    {"-c"},
    "python":  {"-c"},
    "python2": {"-c"},
    "python3": {"-c"},
    "perl":    {"-e", "-E"},
    "ruby":    {"-e"},
    "node":    {"-e", "--eval"},
    "php":     {"-r"},
}


def check_token(t):
    """비밀 경로 토큰 여부 검사. ('FILE', token) / ('GLOB', token) / None 리턴."""
    if not t:
        return None
    t = t.strip("'\"()<>;|&{}@")
    if not t:
        return None
    if SAFE_ENV_TOKEN.search(t):
        return None
    if SECRET_PATH_TOKEN.match(t):
        return ("FILE", t)
    if GLOB_META.search(t) and SECRET_GLOB_SUBSTR.search(t):
        return ("GLOB", t)
    return None


def raw_scan(payload):
    """인터프리터 -c/-e 페이로드 안에서 비밀 경로 substring 을 잡는다."""
    if not payload:
        return None
    # SAFE_ENV_TOKEN 부분은 raw 매치도 허용 — `.env.example` 언급 통과.
    for m in SECRET_PATH_RAW.finditer(payload):
        t = m.group(0)
        if SAFE_ENV_TOKEN.search(t) or SAFE_ENV_TOKEN.search("/" + t):
            continue
        return ("FILE", t)
    return None


def expand_substitutions(s):
    """$(...) 와 backtick(...) 본문을 yield. 단일 레벨만 지원 (실무상 충분).
    backtick 리터럴은 bash `$(...)` 와 충돌해 외부 헤더독 파싱이 깨지므로 \\x60 escape 로 작성."""
    for m in re.finditer(r"\$\(([^()]*(?:\([^()]*\)[^()]*)*)\)", s):
        yield m.group(1)
    for m in re.finditer(r"\x60([^\x60]*)\x60", s):
        yield m.group(1)


def split_parts(s):
    """; & | && || 로 sub-command 분리. 쉘 quoting 은 근사 처리."""
    return re.split(r"(?<!\\)[;&|]+|\s*&&\s*|\s*\|\|\s*", s)


def scan(cmd_str, depth=0):
    if not cmd_str or depth > 6:
        return None

    # 1) command substitutions / backticks 재귀
    for sub in expand_substitutions(cmd_str):
        r = scan(sub, depth + 1)
        if r:
            return r

    # 2) sub-command 분리
    for part in split_parts(cmd_str):
        p = part.strip()
        # 괄호 서브쉘 strip (정확한 매칭은 아니지만 흔한 케이스 커버)
        p = re.sub(r"^\(\s*", "", p)
        p = re.sub(r"\s*\)\s*$", "", p)
        if not p:
            continue
        try:
            toks = shlex.split(p, posix=True)
        except ValueError:
            toks = p.split()
        if not toks:
            continue

        # 선행 환경변수 할당 건너뜀 (FOO=bar cmd ...)
        i = 0
        while i < len(toks) and re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", toks[i]):
            i += 1
        if i >= len(toks):
            continue
        head = toks[i]
        head_base = head.rsplit("/", 1)[-1]
        rest = toks[i + 1:]

        # echo/printf/print — 시크릿 환경변수 노출 검사
        if head_base in ECHO_CMDS:
            joined = " ".join(rest)
            m = SECRET_VAR.search(joined)
            if m:
                return ("ECHO_VAR", m.group(1))
            # echo 의 positional 도 일반 검사로 떨어짐

        if head_base == "printenv":
            if not rest:
                return ("PRINTENV_ALL", "")
            m = SECRET_VAR.search(" ".join(rest))
            if m:
                return ("PRINTENV", m.group(1))

        if head_base == "env" and not rest:
            return ("ENV_DUMP", "")

        # 인터프리터 -c/-e 본문 재귀 + raw_scan 이중 검사
        flags = INTERPRETER_FLAGS.get(head_base, set())
        if flags:
            k = 0
            while k < len(rest):
                tok = rest[k]
                payload = None
                if tok in flags and k + 1 < len(rest):
                    payload = rest[k + 1]
                    k += 2
                elif any(tok.startswith(f + "=") for f in flags):
                    payload = tok.split("=", 1)[1]
                    k += 1
                else:
                    k += 1
                if payload is not None:
                    r = raw_scan(payload) or scan(payload, depth + 1)
                    if r:
                        return r

        # eval — 인자 전체를 명령으로 재귀 (단일 인자/공백 분리 모두 커버)
        if head_base == "eval" and rest:
            r = scan(" ".join(rest), depth + 1) or raw_scan(" ".join(rest))
            if r:
                return r

        # generic positional scan — 모든 sub-command 의 positional 토큰을 검사.
        # source / . / xargs / find / ls / git / perl(payload 뒤 파일 arg) 등 모두 자동 커버.
        for tok in rest:
            # `--long=value` 옵션은 값만 추출
            if tok.startswith("--") and "=" in tok:
                tok = tok.split("=", 1)[1]
            # boolean flag 는 skip
            elif tok.startswith("-") and tok not in ("-", "--"):
                # `-mfoo` 같은 short cluster 안의 메시지에 비밀 경로가 박힌 케이스 보호
                # → flag 토큰은 raw_scan 으로 보조 검사
                r = raw_scan(tok)
                if r:
                    return r
                continue
            r = check_token(tok)
            if r:
                return r

    return None


result = scan(cmd)
if result is None:
    print("OK")
else:
    kind, val = result
    print(f"{kind}:{val[:120]}")
PY
)

if [[ "$verdict" == "OK" ]]; then
  exit 0
fi

case "$verdict" in
  FILE:*|GLOB:*)
    cat >&2 <<'EOF'
🛑 비밀 파일 출력 차단됨.

본 워크스페이스 정책:
- .env / .env.* / credentials.json / secrets.{yml,yaml,json}
  / *.pem|*.p12|*.pfx|*.keystore|*.jks
  / id_(rsa|dsa|ecdsa|ed25519|ed448) (.pub/.backup/.old/_personal 등 변종 포함)
  는 어떤 명령(직접 cat/grep, bash -lc 래퍼, python -c, source, eval, $() 서브쉘 등)으로도 출력·복사할 수 없습니다.
- 토큰/키 값이 stdout 으로 새면 conversation 컨텍스트에 남고, 로그·캐시에 잔존합니다.
- (참고: .claude-local/ 는 팀원 개인 용도로 차단 대상에서 제외됩니다. 단, .claude-local/ 안의 .env 는 위 패턴에 잡힙니다.)

→ 사용자가 직접 확인이 필요하면 터미널에서 직접 실행하세요. Claude 는 본 경로의 값을 모릅니다.
→ 정당한 사유가 있는 1회성 우회: 명령에 '# !no-secret-guard' 주석을 명시 (감사 대상).
EOF
    echo "검출: $verdict" >&2
    ;;
  ECHO_VAR:*|PRINTENV:*)
    var="${verdict#*:}"
    echo "🛑 시크릿 환경변수 출력 차단됨." >&2
    echo "" >&2
    echo "변수: \$$var" >&2
    cat >&2 <<'EOF'

본 워크스페이스 정책:
- *TOKEN* / *KEY* / *SECRET* / *PASSWORD* / *PAT* / *CREDENTIAL* / *AUTH* / *ACCESS_KEY* / *PRIVATE_KEY*
  변수의 값을 echo / printf / printenv 로 표시할 수 없습니다.

→ 변수 설정 여부만 확인: [[ -n "$VAR" ]] && echo set || echo unset
→ 길이만 확인: echo "${#VAR}"
→ 정당한 사유가 있는 1회성 우회: '# !no-secret-guard' 주석.
EOF
    ;;
  ENV_DUMP|PRINTENV_ALL)
    cat >&2 <<'EOF'
🛑 전체 환경 변수 덤프 차단됨.

'env' / 'printenv' (인자 없이) 는 모든 환경 변수를 출력하므로 시크릿 노출 위험이 큽니다.

→ 특정 변수가 set 인지만 확인: [[ -n "$VAR_NAME" ]] && echo set || echo unset
→ 변수 이름 목록만 (값 없이): compgen -e | grep -v -iE '(token|key|secret|password|pat|credential|auth)'
→ 정당한 사유가 있는 1회성 우회: '# !no-secret-guard' 주석.
EOF
    ;;
  *)
    echo "🛑 비밀 노출 가능 명령 차단됨: $verdict" >&2
    ;;
esac
exit 2
