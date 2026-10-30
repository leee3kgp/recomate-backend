# 컨테이너 구성과 AWS 배포

검증한 백엔드 이미지를 ECR에 올려 기존 API Gateway REST API → Lambda 구조로 배포하는 기준입니다. 자동 AWS 배포 파이프라인은 포함하지 않습니다.

로컬 준비와 실행 명령은 [개발 환경](development.md)을 참고합니다.

## 장르 목록 API 배포 (2026-10-29)

장르 목록 `GET /api/genres`와 사전 요청 `OPTIONS /api/genres`를 기존 Lambda에 연결하도록 `infra/user-api.yaml`을 확장했습니다. GET에는 기존 Cognito Authorizer를 적용하고 OPTIONS는 인증 없이 전달합니다. 사용자·세션 경로와 공유 RecommendationSession 테이블 설정을 유지하며 별도 Lambda·장르 테이블·데이터 권한을 추가하지 않습니다.

새 `ApiDeploymentGenreCatalogV1` 스냅샷을 기존 사용자·세션 메서드와 새 장르 메서드 모두가 준비된 뒤 생성하고 dev Stage에 적용했습니다. 현재 배포 ID는 `uky3vl`입니다. 아래 #6 이미지·실제 호출 결과는 이전 배포 기록이며 장르 기능을 포함하지 않습니다.

main의 CI 수정도 반영했습니다. GitHub Actions는 `runtime-ci` target으로 Lambda Adapter 다운로드 없이 컨테이너를 검사하고, Compose 및 Lambda 배포는 Adapter가 포함된 `runtime` target을 사용합니다. 두 target은 같은 애플리케이션과 공통 런타임 기반을 사용합니다.

현재 배포 이미지는 `src-d955fea7152f7939`, digest `sha256:f691338b35827ab3579f8317749a8a39342f9e60a5427e38f4d7e7994725911d`입니다. CodeBuild `39fd4fbf-42ff-4714-a49e-799b45012dfe`의 Docker 빌드·컨테이너 검사가 성공했고 ECR basic scan 발견 항목은 0개였습니다. 변경 계획의 서비스 사전 검증 오류 0개를 확인한 뒤 승인받아 적용했으며, 실제 장르·사용자·세션 검증 77개 항목도 통과했습니다. 세부 범위와 재현 기준은 [장르 API 문서](genre-api.md)를 따릅니다.

## #6 실제 세션 연결 배포 (2026-10-28)

공유 BE2 테이블을 `infra/sessions.yaml`의 `recomate-sessions-dev` 스택으로 생성하고, 사용자 API 스택의 `ExistingSessionsTableName=RecommendationSession`에 연결했습니다. 상세 키·삭제 보장·IAM 범위와 검증 기록은 [세션 연결 문서](session-integration.md)를 따릅니다. 이전 validation-sessions 테이블은 PreserveLegacyValidationTable=true로 유지해 기존 데이터와 스택 소유권을 보존합니다.

POST /api/sessions와 OPTIONS를 기존 Lambda에 연결하고, 새 Deployment 스냅샷으로 dev Stage에 반영했습니다. IAM Policy Autopilot을 다시 실행해 ACTIVE 조건 트랜잭션과 Scan 삭제 권한을 실제 테이블에 한정해 적용했습니다. 두 스택 적용이 완료됐고 로컬 테스트 110개, 실제 AWS 검증 항목 66개를 통과했습니다. API·공유 테이블은 개발 연동에 사용하도록 유지하며 테스트 사용자·세션은 정리했습니다.

#6 당시 이미지 태그는 `src-f603fb4e2abe4ed6`, digest는 `sha256:6393e97c1e98590ae957dbd741fc679e633291caccbf92b096b58d948f04d6f1`입니다. CodeBuild 실행 `70379989-06c3-4836-a9a9-f4c3b31d5dc8`의 Docker 빌드·읽기 전용 컨테이너 인증/CORS 검사와 ECR basic scan(COMPLETE, 발견 항목 0개)을 확인했습니다. 이 배포 검증은 2026-10-28 기록이며 위 장르 추가 배포와 구분합니다.

## 공통 실행 기준

실제 값은 [Dockerfile](../Dockerfile)과 [Compose](../compose.yaml)에서 관리합니다.

| 항목 | 설정 |
| --- | --- |
| 빌드·실행 Java | 21 |
| 기반 이미지 | JDK·JRE·Lambda Web Adapter의 digest 고정 |
| 배포 아키텍처 | `linux/amd64` / Lambda `x86_64` |
| HTTP 포트 | 8080 |
| 파일 쓰기 | 루트는 읽기 전용, 임시 파일은 `/tmp` |
| 실행 사용자 | 비관리자 UID 10001 |
| Lambda 연결 | AWS Lambda Web Adapter 1.1.0 |
| 개발용 Lambda 메모리 | 3008 MiB; 첫 요청 지연을 줄이기 위한 CPU 할당 |

ARM 기반 Mac에서는 amd64 에뮬레이션으로 빌드가 느릴 수 있습니다. 기반 이미지의 보안 패치는 digest 갱신과 재검증으로 반영합니다.

일반 테스트는 개인 AWS 로그인 없이 통과하도록 유지합니다. 컨테이너 시작 확인은 TCP 연결 검사이므로 DB·JWT·업무 API의 정상 동작까지 보장하지 않습니다.

## Docker가 없는 배포 담당 PC에서 준비

현재 PC에는 Docker가 없어 AWS CodeBuild에서 기존 Dockerfile을 빌드하는 구성을 추가했습니다. 일반 개발자의 Docker 실행 구성과 별개이며, CodeBuild 실행·ECR/S3 저장·KMS 로그 암호화 비용이 발생할 수 있습니다. 새 리소스는 모두 ap-southeast-2에 둡니다.

| 파일 | 목적 |
| --- | --- |
| `infra/build.yaml` | 비공개 소스 ZIP 버킷, immutable ECR 저장소, CodeBuild 역할·프로젝트, 암호화된 빌드 로그 |
| `infra/user-api.yaml` | 사용자 테이블, 이전 검증용 테이블 보존, 공유 BE2 테이블 연결, 실행 역할, Lambda, 기존 API Gateway의 사용자·세션 생성 경로와 개발 Stage |
| `infra/sessions.yaml` | PR #9의 sessionId 기본 키·사용자별 GSI를 사용하는 공유 RecommendationSession 테이블 |
| `buildspec.yml` | Docker 이미지 빌드, 읽기 전용 컨테이너의 401·CORS 확인, 성공한 이미지만 ECR push |
| `scripts/package-source.py` | 환경 설정·자격 증명을 제외한 소스 ZIP과 내용 해시 생성 |
| `scripts/generate-runtime-policy.ps1` | Java SDK 호출을 Autopilot으로 분석하여 IAM 정책 초안 생성. 업로드·적용하지 않음 |
| `infra/validation-tools.json` | 프로젝트 내부 검증 도구의 버전과 공식 다운로드 체크섬 |

배포 담당자는 먼저 `python scripts/package-source.py`로 `.gradle/deployment/source.zip`과 manifest를 만듭니다. ZIP은 명시한 빌드 파일과 Java 소스만 포함하며 로컬 설정, `.env`, `.aws`, `.git`, 기존 빌드 결과는 포함하지 않습니다. 같은 소스는 줄바꿈·파일 시각 차이와 무관하게 같은 ZIP 내용 해시를 만듭니다.

검증 순서는 로컬 cfn-lint, cfn-guard 규칙 검사, CloudFormation 서비스 검사입니다. 2026-10-27에 두 템플릿의 cfn-lint 오류·경고 0개, 선택한 공식 Guard 규칙 위반 0개, 변경 계획의 서비스 검사를 확인한 뒤 개발용 배포를 완료했습니다. 적용한 공식 규칙의 버전·목록은 `infra/validation-tools.json`에 고정했습니다. DynamoDB는 기본 AWS-owned key 암호화를 사용하므로 명시적 KMS 테이블 키를 강제하는 규칙은 이 선택에 적용하지 않았습니다.

기존 Cognito User Pool·앱 클라이언트·Authorizer·jwt-test Stage는 재생성하지 않습니다. 사용자 API 5개에는 기존 Authorizer를 적용하고 OPTIONS만 인증 없이 Lambda로 전달합니다. Gateway가 Lambda보다 먼저 거절하는 401에도 프론트 Origin 헤더가 나오도록 기존 UNAUTHORIZED·ACCESS_DENIED GatewayResponse의 상태 코드·JSON을 보존하고 CORS 헤더를 추가했습니다. 이 두 응답은 기존 공유 API 설정이므로 새 스택에서 소유권을 가져오지 않습니다.

이전 배포는 독립된 validation-sessions 테이블로 탈퇴를 검증했습니다. 이번 변경은 PR #9의 공유 sessionId 테이블과 ACTIVE 조건 트랜잭션을 대상으로 하며, 공유 테이블 생성과 애플리케이션 변경을 함께 검증해야 합니다.

CodeBuild를 시작할 때는 소스 ZIP의 S3 위치·버전을 고정하고, manifest의 SOURCE_SHA256과 `src-<해시 앞 16자리>` IMAGE_TAG를 전달합니다. 빌드 역할은 앱 데이터에 접근하지 않습니다. 실행 역할의 앱 권한은 Java 소스 분석 결과를 실제 테이블·User Pool ARN으로 좁혀 `RuntimeDataPolicy`로 관리합니다. 배포에는 push 결과의 정확한 이미지 digest를 사용합니다.

이전 2026-10-27 배포는 `recomate-build-dev`, `recomate-user-api-dev` 스택과 이미지 태그 `src-23a029271064a66b`, digest `sha256:cd52eefa7db6921b272ca0215d864dd5d7a1f0e51fc94346f9392a549bbde734`을 사용했습니다. 당시 실제 API 통합 검증 53개와 이미지 basic scan 완료(발견 항목 0개)를 확인했습니다. 1536 MiB에서 첫 요청이 26.3초로 관측되어 3008 MiB로 조정했고, 업데이트 후 첫 사전 요청 7.4초·연속 요청 0.65초를 관측했습니다. 이번 배포도 3008 MiB를 유지하며 위 공유 테이블 스택을 추가했습니다. 메모리 증가는 CPU 할당과 실행 시간당 비용에 영향을 주며 상시 실행 용량은 예약하지 않습니다. 운영 부하 검증은 별도 확인 대상입니다.

현재 프로젝트에는 지속 보관용 CloudTrail trail이 없습니다. CloudTrail 이벤트 기록의 보존 범위와 장기 감사 로그는 별도로 검토해야 하며, 이 작업에서 감사 설정을 자동 변경하지 않습니다.

## 배포 이미지 준비

```text
docker buildx build --platform linux/amd64 --provenance=false --sbom=false --load --tag recomate-backend:local .
docker compose up --no-build -d
docker compose ps
```

Lambda 배포용 이미지는 단일 아키텍처로 만들고 provenance/SBOM attestation을 끕니다. 테스트한 최종 이미지를 다시 빌드하지 않고 그대로 push한 뒤, 최종 ECR digest로 배포 버전을 고정합니다.

## AWS 배포 순서

로컬에서 이미지를 직접 빌드할 때는 Docker가 필요하고, 위 CodeBuild 경로를 쓰면 담당 PC의 Docker는 필요하지 않습니다. AWS 작업에는 프로젝트 접근 권한이 필요하며, CLI를 사용할 때는 AWS CLI를 준비합니다. 모든 Regional 리소스는 `ap-southeast-2`에 둡니다.

1. 프로젝트 플랜과 ECR·Lambda 사용 가능 상태를 확인합니다.
2. 같은 리전의 private ECR 저장소를 준비합니다. 소스 내용 해시 기반 태그와 태그 덮어쓰기 방지 정책을 사용합니다.
3. 본인 AWS 자격 증명으로 ECR에 로그인하고 검증한 이미지를 push합니다.
4. `repository-uri@sha256:...` 형식으로 Lambda 이미지를 지정합니다.
5. x86_64 아키텍처와 실행 역할을 설정하고, 실제 시작 시간·부하에 맞춰 메모리와 타임아웃을 조정합니다.
6. 실행 역할에는 로그 및 구현된 API가 사용하는 DynamoDB·Cognito 작업만 허용합니다.
7. API Gateway의 실제 서비스 경로에 Lambda proxy 통합과 기존 Cognito Authorizer를 적용합니다.
8. Stage를 배포하고 아래 통합 검증을 수행합니다.

이 프로젝트의 로컬 AWS CLI 작업에는 `--profile recomate --region ap-southeast-2`를 명시합니다. 개인 프로필·액세스 키를 이미지나 Lambda에 복사하지 않습니다. Lambda는 실행 역할과 런타임 리전을 사용합니다.

## 배포 검증

- 테스트 통과, 읽기 전용 컨테이너 기동, ECR·Lambda 이미지 digest 일치.
- cold start·연속 호출의 시작 시간, 메모리, 타임아웃과 외부 API 연결.
- 실제 API 경로와 `/api` 접두사 전달.
- 정상 ID Token 및 누락·위조·만료 토큰의 인증 결과.
- 프론트 Origin의 CORS, 공통 오류 응답, 실제 데이터 저장·삭제와 실행 권한.

기존 `/jwt-test/auth-test` MOCK 테스트와 실제 서비스 API 검증은 별개입니다. [컨테이너 CI](../.github/workflows/container-check.yml)는 빌드·테스트·일반 컨테이너 기동을 검사하며, Lambda 이벤트 변환과 FE 통합 테스트는 실제 개발용 배포에서 추가로 확인해야 합니다.

## 공식 참고 자료

- [AWS Lambda Web Adapter](https://github.com/aws/aws-lambda-web-adapter)
- [Lambda 컨테이너 이미지](https://docs.aws.amazon.com/lambda/latest/dg/images-create.html)
- [Docker Compose 빌드 설정](https://docs.docker.com/reference/compose-file/build/)
