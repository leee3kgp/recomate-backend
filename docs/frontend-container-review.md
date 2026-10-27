# 프론트·백엔드 호환성 점검 기록

점검일: 2026-10-27
대상: 다운로드된 `recomate-frontend-main` 압축 해제본과 백엔드 `feature/user-api`의 환경 구성 변경.

이 문서는 해당 시점의 점검 기록입니다. 최신 개발·배포 절차는 [개발 환경](development.md)과 [컨테이너 배포](container-deployment.md)에서 관리합니다.

## 결론

백엔드 환경 구성 변경에서 프론트 소스와 직접 겹치는 파일이나 API 계약 변경은 발견하지 않았습니다. 실제 서비스 연동은 아래 기능 작업이 남아 있어 완료되지 않았습니다.

## API 명세 대조

[API PDF](https://drive.google.com/file/d/13zQs02W2zvoBRIRivgKqt1UiYHEmRXel/view?usp=drivesdk), [API 목록 시트](https://docs.google.com/spreadsheets/d/1akK5Z77UUIF6IlJo4wU10IBKA3Bl5Vo-JHw6VhnbNyg/edit?usp=drivesdk), [Cognito 상세 명세](https://docs.google.com/document/d/1Mc8vwIC5rDwjxPbLsl8dv19J-_x-MlXKNlOa8LlnzW0/edit?usp=drivesdk)를 기준으로 확인했습니다.

| 항목 | 결과 |
| --- | --- |
| Cognito 직접 가입·SRP 로그인 | 명세와 일치 |
| User Pool·Client ID | 기존 AWS 점검 값과 일치 |
| ID Token의 Bearer 헤더 전달 | 명세와 일치 |
| SDK 토큰 갱신 | `fetchAuthSession` 사용 |
| 내부 장르 코드 | 10개 모두 일치 |
| 세션 요청 형태 | `participantCount`, 참여자의 `index + genres` 일치 |
| 참여 인원 | 명세의 2~6명 범위와 일치 |

## 후속 작업

| 항목 | 확인한 상태 | 처리 범위 |
| --- | --- | --- |
| 백엔드 JWT·CORS | 사용자 API·JWT 검증·CORS 설정이 없고 기본 Basic 인증 상태 | #6 사용자 API 구현·검증 |
| 프론트 세션 생성 | `js/select.js:113`에서 데이터를 만들지만 실제 호출 대신 대기·alert 처리 | 세션 API 구현 및 별도 FE 연동 |
| API 주소·접두사 | PDF는 `/users/me`, 시트는 `/api/users/me`; FE 공통 API 주소·프록시 없음 | #6 명세 정리 및 FE 연동 |
| API 401 처리 | `js/auth-token.js:90`의 `authFetch`에 1회 강제 갱신·재시도 없음 | FE 인증 보완 |
| 닉네임 길이 | 가입 JS·HTML에 20자 제한 없음 | FE 인증 보완; BE에서도 검증 |
| FE 실행·빌드 | npm scripts 없음; 진입 페이지가 `pages/index.html` | FE 개발·빌드 환경 설정 |

현재 Compose는 백엔드만 실행합니다. FE는 npm 명령과 Vite 진입점, 개발·배포별 API 주소를 별도로 준비해야 합니다.

### CORS 재현 결과

로컬 JAR에 아래 브라우저 사전 요청 조건을 재현했습니다. Origin은 검사에 사용한 예시 값입니다.

```http
OPTIONS /api/users/me
Origin: http://localhost:5173
Access-Control-Request-Method: GET
Access-Control-Request-Headers: authorization
```

결과는 `401`, CORS 허용 헤더 없음, `WWW-Authenticate: Basic`이었습니다. API Gateway의 Cognito 검증만으로 애플리케이션의 기본 Basic 인증 설정이 해결되지는 않습니다. 실제 Cognito 토큰으로 새 로그인 통합 테스트를 수행한 것은 아닙니다.

## 변경 내용의 팀 공유 요약

이번 작업은 Java 21·Docker 기반의 백엔드 공통 실행 및 Lambda 배포 준비입니다. API 경로·JSON·Cognito 직접 로그인·ID Token 계약은 바꾸지 않았습니다. 사용자 API와 DB 연결, JWT·CORS 및 FE 호출은 기능 작업에서 이어서 구현해야 합니다. 파일별 환경 설명은 개발·배포 문서에서 관리합니다.

## 점검 당시 검증 상태

| 확인 완료 | 남은 검증 |
| --- | --- |
| FE JavaScript 8개 문법·장르 코드 대조 | FE npm 설치·배포 빌드 |
| Java 21 자동 준비, 기본 테스트 1개, JAR 생성·기동 | 실제 Docker 이미지 빌드·실행 |
| Compose 스키마·CI YAML 문법 | GitHub CI 실행 |
| 기반 이미지 태그·amd64 digest 존재 | 실제 Lambda·FE·DB 통합 검증 |
| 로컬 CORS preflight 차단 결과 | 정상·오류 토큰의 실제 서비스 API 호출 |

당시 로컬에는 Docker가 없었고 변경은 커밋·push 전이었습니다. FE 잠금 파일은 Amplify 6.22.1, Vite 8.3.3이며 Node 요구 사항은 `^20.19.0 || >=22.12.0`입니다. 다운로드된 압축본에 대한 검사이므로 미공유 변경이나 다른 브랜치 전체까지 검증한 결과는 아닙니다.
