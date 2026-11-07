# 배포 환경 자동 점검 (#22)

[scripts/live-check.py](../scripts/live-check.py)는 배포된 프론트 주소와 dev API를 실제로 호출해 인증·프로필·장르·세션·추천·제외·탈퇴·CORS를 확인합니다. 백엔드 이미지, 프론트, CORS·Cognito·API Gateway 설정을 배포한 뒤 실행하면 이전에 확인한 흐름이 깨지지 않았는지 몇 분 안에 다시 확인할 수 있습니다.

## 언제 실행하나

| 변경 | 실행 |
| --- | --- |
| 백엔드 Lambda 이미지 배포 | 전체 |
| 프론트 배포 | 전체 (`version.json`의 커밋 확인 포함) |
| `AllowedOrigin`, API Gateway 경로, Cognito 설정 변경 | 전체 |
| 문서만 변경 | 불필요 |

화면 조작(입력 검사 문구, 모달, 이메일 인증 코드, 모바일 레이아웃)은 이 스크립트 범위가 아닙니다. 해당 화면이 바뀌었을 때만 [#22](https://github.com/leee3kgp/recomate-backend/issues/22)의 수동 항목 중 관련 항목을 확인합니다.

## 준비

- 프로젝트 AWS 접근 권한과 `aws login --profile recomate`로 받은 자격 증명. 임시 Cognito 사용자 생성·삭제와 세션 테이블 조회에 사용합니다.
- Python 3.11 이상과 의존성. 프로젝트 도구 폴더를 쓰면 저장소에 파일이 생기지 않습니다(`.gradle/`은 Git에서 제외).

```text
python -m venv .gradle/deployment-tools/venv
.gradle/deployment-tools/venv/Scripts/python -m pip install -r scripts/live-check-requirements.txt
```

macOS·Linux에서는 `Scripts/python` 대신 `bin/python`을 사용합니다.

## 실행

```text
.gradle/deployment-tools/venv/Scripts/python -I scripts/live-check.py
```

기본값은 현재 dev 환경입니다. 다른 환경은 옵션으로 지정합니다.

| 옵션 | 기본값 | 설명 |
| --- | --- | --- |
| `--api-base` | dev API Stage 주소 | 점검할 API |
| `--site-url` | `https://d15wymuj9qezfe.cloudfront.net` | 프론트 주소. `""`이면 사이트 점검 생략 |
| `--origin` | 사이트 주소, `http://localhost:5173` | CORS 허용을 기대하는 주소. 여러 번 지정 가능 |
| `--skip-recommendation` | 꺼짐 | TMDB를 호출하는 추천 생성·제외 점검 생략 (회원·비회원 모두) |
| `--profile`, `--region` | `recomate`, `ap-southeast-2` | AWS 자격 증명 |

마지막 줄 `SUMMARY 69/69 passed`처럼 실패가 없으면 종료 코드 0, 하나라도 실패하면 실패 항목을 출력하고 1을 반환합니다.

## 점검 항목 (75개)

- 프론트 사이트: 8개 페이지, HTTP→HTTPS, HTML `no-cache`, 보안 헤더, `version.json`의 커밋과 API 주소, 없는 주소 404
- 미인증 요청: 로그인이 필요한 11개 경로 401, 공개 경로까지 13개의 CORS 사전 요청 허용, 목록에 없는 주소 거절
- 비회원(토큰 없음): 장르 목록과 CORS, TOP 3 응답(세션 ID 없음·캐시 금지), "이미 봤어요" 작품 제외, 잘못된 참여자 400, 잘못된 토큰 401
- 프로필: 첫 조회 시 생성, 응답 CORS·캐시 방지, 닉네임 빈 값·공백·21자 거절, 앞뒤 공백 제거·이모지·20자 저장
- 장르: 10개 목록, 잘못된 코드·중복·배열 아님 거절, 저장·재조회·전체 해제
- 토큰: 위조 토큰 401, 갱신 토큰으로 받은 새 토큰 사용
- 세션: 생성·목록·TOP 3·제외·상세 반영·재생성 409·없는 세션 404·삭제
- 탈퇴: 204, 기존 토큰 401, 재요청 204, Cognito 사용자·세션 삭제
- 닉네임 없는 가입자: `NICKNAME_REQUIRED` 409 후 닉네임 저장으로 복구

## 안전장치

- 임시 사용자는 `recomate-live-check-…@example.test` 형식이며 이메일을 보내지 않습니다(`MessageAction=SUPPRESS`). 비밀번호는 실행 중에만 무작위로 만들고 토큰과 함께 출력하지 않습니다.
- 점검 중 실패해도 마지막에 앱 탈퇴 API와 Cognito 삭제로 임시 사용자와 세션을 정리합니다. 사용자 테이블에는 기존 탈퇴 정책대로 48시간 TTL 탈퇴 표시만 남습니다.
- 추천 생성은 TMDB를 호출합니다. 외부 장애가 의심되면 `--skip-recommendation`으로 나머지를 확인합니다.

## 실행 기록

- 2026-11-07: dev(이미지 `src-80fb45f0f51c12b9`, 배포 ID `nozpie`) 75/75 통과. 비회원 장르·추천·제외 항목 포함.
- 2026-11-03: dev(이미지 `src-a46b4b3fc58f8d58`, 프론트 `ee0a98d`) 69/69 통과. 등록하지 않은 주소를 `--origin`으로 넣은 실행에서는 CORS 실패 3개와 종료 코드 1을 보고하고 임시 사용자를 정리해, 실패를 실제로 감지함을 확인했습니다.
