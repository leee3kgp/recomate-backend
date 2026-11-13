# 프론트엔드 배포 (#20)

[recomate-frontend](https://github.com/leee3kgp/recomate-frontend)의 Vite 빌드 결과를 비공개 S3에 올리고 CloudFront HTTPS 주소로 서비스합니다. 백엔드 이미지와 같이 CodeBuild가 지정한 커밋을 빌드·배포하며, 프론트 저장소에는 배포용 파일을 추가하지 않습니다. 템플릿은 [infra/frontend.yaml](../infra/frontend.yaml)입니다.

## 구성

| 구성 | 설정 | 이유 |
| --- | --- | --- |
| S3 버킷 (ap-southeast-2) | 퍼블릭 차단, AES256 암호화, 버전 관리, HTTPS 전용, 교체된 파일 30일 후 삭제 | 정적 파일 보관과 롤백 |
| CloudFront | Origin Access Control로 이 배포만 버킷을 읽음, HTTP→HTTPS 리디렉션, HTTP/2·3, 압축, `PriceClass_200`(한국 포함) | 기본 HTTPS 주소와 빠른 전송 |
| 응답 헤더 | 관리형 `SecurityHeadersPolicy`: `Strict-Transport-Security: max-age=31536000`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: SAMEORIGIN`, `Referrer-Policy: strict-origin-when-cross-origin`, `X-XSS-Protection: 1; mode=block` | 다른 사이트의 프레임 삽입·MIME 추측·HTTP 접속을 막는 기본 보안 헤더 |
| 캐시 | `assets/*`(파일 이름에 해시 포함) 1년 `immutable`, HTML·기타 파일 `no-cache` | 새 배포가 즉시 보이고 재방문은 빠름 |
| 없는 주소 | 비공개 버킷의 403을 404 상태와 프론트의 404 페이지(`/pages/404.html`)로 응답 | 잘못된 주소임을 알리고 홈·추천으로 안내 |
| CodeBuild | 공개 저장소를 인증 없이 복제, **main에 포함된 커밋만** 빌드, Node 22 (`standard:8.0`) | GitHub·AWS 키 저장 없이 재현 가능한 배포 |
| 배포 로그 | CloudWatch 7일 보관, 기본 저장 암호화 | 별도 유료 KMS 키 없이 짧게 보관 |

GitHub Actions에서 AWS로 바로 배포하는 OIDC 방식은 이 AWS 프로젝트의 관리형 SCP가 OIDC 공급자를 차단해 사용하지 않습니다. 접근 키를 GitHub에 저장하는 방식도 사용하지 않습니다.

## 배포 순서

배포 담당자는 프로젝트 AWS 접근 권한이 필요합니다. CLI는 `--profile recomate --region ap-southeast-2`를 명시합니다.

1. 배포할 프론트 main 커밋의 전체 SHA를 확인합니다.

   ```text
   git ls-remote https://github.com/leee3kgp/recomate-frontend.git refs/heads/main
   ```

2. 그 커밋으로 배포를 시작합니다. 40자 SHA가 아니거나 main에 없는 커밋이면 빌드가 실패합니다.

   ```text
   aws codebuild start-build --project-name recomate-frontend-dev --environment-variables-override name=FRONTEND_COMMIT,value=<SHA>,type=PLAINTEXT --profile recomate --region ap-southeast-2
   ```

3. 빌드는 `npm ci` → `npm run build` → 페이지 확인(첫 화면과 404 페이지가 없으면 중단) → 해시 파일 업로드 → HTML 업로드 → CloudFront 캐시 무효화 완료 대기 → 사용하지 않는 해시 파일 삭제 순서로 진행합니다. HTML보다 해시 파일을 먼저 올리므로 배포 중에도 화면이 깨지지 않습니다.
4. `https://<배포 주소>/version.json`에서 배포된 커밋·시각·API 주소를 확인합니다.

API 주소는 스택 파라미터 `ApiBaseUrl`이 빌드 시 `VITE_API_BASE_URL`로 들어갑니다. 기본값은 dev API입니다.

## 롤백

이전 커밋 SHA로 2번을 다시 실행합니다. 같은 커밋은 같은 결과를 만듭니다. S3 버전 관리로 교체된 파일은 30일간 남습니다.

없는 주소 응답이 `/pages/404.html`을 사용하므로, 빌드 결과에 404 페이지가 없는 커밋(프론트 PR #53 병합 `9416f5e` 이전)은 빌드 단계에서 거절됩니다. 그 이전 화면이 필요하면 404 페이지를 포함한 커밋에서 되돌리는 PR을 만들어 배포합니다.

## 백엔드 CORS

배포 주소를 백엔드 스택 `recomate-user-api-dev`의 `AllowedOrigin`에 쉼표로 추가합니다. 로컬 개발 주소(`http://localhost:5173`, `http://127.0.0.1:5173`)는 유지합니다. 상세 기준은 [컨테이너 배포](container-deployment.md)를 따릅니다.

## 검증 기준

- 배포 주소의 첫 화면과 11개 페이지가 200으로 열리고 HTTP 요청은 HTTPS로 리디렉션됩니다.
- 없는 주소는 단일·중첩 경로 모두 404 상태와 404 페이지로 응답합니다.
- 보안 헤더와 파일별 `Cache-Control`이 위 기준과 같습니다.
- S3 버킷 주소로 직접 요청하면 거절됩니다.
- 배포 주소를 Origin으로 한 API 사전 요청이 허용됩니다.
- 위 항목은 [배포 환경 자동 점검](live-check.md)의 `scripts/live-check.py`가 함께 확인합니다(S3 직접 접근 제외). 배포 후 실행해 `SUMMARY`에 실패가 없는지 확인합니다.

## 배포 기록 (2026-11-12)

- 프론트 main `6f2251e`(비밀번호 재설정·약관·개인정보처리방침·404 페이지 #51, 빌드 누락·404 링크 수정 #53, 비밀번호 조건 Cognito 일치 #55 포함)를 CodeBuild `71dd353c-e3a0-46aa-9b09-58f2fe31c4cc`로 재배포했습니다. `version.json` 커밋 일치와 새 페이지 5개 200을 확인했습니다.
- 없는 주소 응답을 `/index.html`에서 `/pages/404.html`로 바꾸고, 404 페이지가 없는 빌드는 게시하지 않도록 빌드 확인을 추가했습니다. 템플릿은 cfn-lint 오류·경고 0개와 선택한 Guard 규칙 중 S3 4개 통과를 확인했습니다. 변경 계획 `frontend-404-page`는 CodeBuild 프로젝트와 CloudFront 배포의 속성 수정 2건이며 교체되는 리소스가 없음을 확인한 뒤 적용했고 스택 UPDATE_COMPLETE입니다.
- 배포 후 `/no-such-page`, `/a/b/c`, `/pages/nope.html`, `/assets/nope.js`가 모두 404 상태와 404 페이지(`no-cache`)로 응답했습니다. 브라우저에서 `/a/b/c`의 404 화면 스타일과 메뉴·버튼 링크가 모두 `/pages/...`의 실제 페이지를 가리키는 것을 확인했습니다.
- [배포 환경 자동 점검](live-check.md) 86개가 모두 통과했습니다. 11/07 추천 흐름 통합으로 삭제된 `select.html`을 점검 목록에서 빼고 새 페이지 5개와 404 페이지 응답 확인을 추가했습니다.
- 비밀번호 재설정용 Cognito 설정을 확인했습니다. User Pool `recomate-users-dev`는 계정 복구 1순위 `verified_email`, 자동 인증 속성 `email`, 이메일 로그인, 인증 코드 방식(`CONFIRM_WITH_CODE`)이고, 앱 클라이언트 `recomate-web-dev`는 클라이언트 시크릿이 없고 사용자 존재 오류 숨김(`PreventUserExistenceErrors`)이 켜져 있어 프론트의 Amplify `resetPassword`·`confirmResetPassword`를 별도 백엔드 API 없이 지원합니다. 설정 변경은 없었고, CloudTrail에서 2026-11-04(CLI)·2026-11-11(브라우저)의 인증번호 요청→비밀번호 변경 성공을 확인했습니다. 메일은 Cognito 기본 발송이라 하루 50통 제한이 있습니다.
- 비밀번호 조건은 실제 User Pool 정책과 대조해 프론트 판정을 일치시켰습니다(프론트 #55). 특수문자는 ASCII 기호 32개와 가운데 공백, 앞뒤 공백 금지, 12~256자입니다.

## 배포 기록 (2026-11-03)

이 절은 당시 기록입니다. 현재 배포는 위 2026-11-12 기록을 따릅니다.

- 스택 `recomate-frontend-dev` 생성(CREATE_COMPLETE). 주소 `https://d15wymuj9qezfe.cloudfront.net`, CloudFront `E3J0V2RBN93ZCL`. 템플릿은 cfn-lint 오류·경고 0개와 선택한 Guard 규칙 중 S3 4개 통과를 확인했습니다.
- 첫 배포는 프론트 main `ee0a98d`(#30·#32·#34 디자인 반영 포함)이며 CodeBuild `561a2f23-bc21-45ce-884c-6932021a1999`에서 Node 22·Vite 8.3.3으로 8개 페이지를 빌드했습니다. Vite의 `VITE_CONFIG_NATIVE_IGNORE_WARNING` 안내는 프론트 설정 로딩 관련 경고이며 빌드 결과에는 영향이 없습니다.
- 배포 주소에서 8개 페이지 200, HTTP→HTTPS 301, 보안 헤더, HTML `no-cache`·해시 파일 1년 캐시, `version.json` 커밋 일치, 번들의 dev API 주소, 없는 주소 404, S3 직접 접근 403을 확인했습니다. 브라우저에서 메인·로그인·회원가입 화면이 콘솔 오류 없이 표시됐습니다.
- 백엔드 `AllowedOrigin`에 배포 주소를 추가했습니다(변경 계획 `cors-frontend-cloudfront`, 이미지·템플릿 유지). 변경 전 배포 주소의 사전 요청은 403이었고, 변경 후 12개 경로 × 배포 주소·localhost 사전 요청 24개 허용, 목록에 없는 주소 403 유지, 임시 사용자의 프로필·장르 조회·장르 저장·세션 목록·탈퇴 응답에 배포 주소 CORS 헤더를 확인했습니다. 임시 사용자는 앱 탈퇴로 삭제했습니다.
- 실제 회원가입(이메일 인증 코드)과 로그인 화면 조작은 FE 인증·프로필 연동 테스트에서 진행합니다.

## 범위 밖과 후속 검토

- 자동 배포: 저장소 소유자가 AWS GitHub 앱(CodeConnections) 설치를 승인하면 같은 CodeBuild 프로젝트에 main push 트리거를 추가할 수 있습니다.
- CSP: 화면이 Google Fonts(메인), jsdelivr의 Chart.js(결과 화면)를 사용하고 Cognito·API·TMDB 이미지에 연결합니다. 디자인 변경에 따라 외부 출처가 바뀌므로(2026-11-03 Bootstrap 제거), 허용 목록을 프론트와 확정한 뒤 별도 응답 헤더 정책으로 추가합니다.
- 커스텀 도메인, WAF(유료), 접속 로그는 포함하지 않았습니다.
- 비용: CloudFront는 월 1TB·1천만 요청까지 무료 범위이고, S3 저장량은 수 MB, 배포 1회 CodeBuild 사용 시간은 수 분입니다.
