# 인기 목록 API

`GET /api/popular`는 장르와 국가로 거른 인기 영화·TV 목록을 반환합니다. 메인 화면의 장르·국가별 인기 목록에 쓰며 **로그인(Cognito ID Token)이 필요합니다.** 비회원은 이 API를 호출하지 않고, 화면에서 블러 처리한 미리보기만 보여줍니다. 전체 스키마는 [OpenAPI](openapi.yaml)의 `listPopular`를 따릅니다. 구현 이슈는 [#24](https://github.com/leee3kgp/recomate-backend/issues/24)입니다.

## 요청

```http
GET /api/popular?genre=ACTION&country=KR
Authorization: Bearer <Cognito ID Token>
```

| 파라미터 | 필수 | 설명 |
| --- | --- | --- |
| `genre` | 아니오 | 장르 마스터 코드 10개 중 하나 (`ACTION`, `SF` …). 대소문자 구분 없음 |
| `country` | 아니오 | 두 글자 ISO 3166-1 국가 코드 (`KR`, `US`, `JP` …). 대소문자 구분 없음 |

- 두 파라미터는 함께 쓰거나 하나만 쓸 수 있고, 둘 다 없으면 전체 인기 목록입니다.
- 비어 있거나 공백뿐인 값은 "필터 없음"으로 처리합니다.

## 응답

상태 코드 `200`, JSON UTF-8입니다. 필터 값은 대문자로 되돌려 주고, 필터가 없으면 `null`입니다.

```json
{
  "genre": "ACTION",
  "country": "KR",
  "items": [
    { "id": 1399, "type": "tv", "title": "예시 드라마", "posterPath": "/example.jpg", "releaseYear": 2024, "voteAverage": 8.1 }
  ]
}
```

- `items`는 영화와 TV를 합쳐 TMDB 인기순으로 정렬한 최대 20개입니다. 조건에 맞는 작품이 없으면 빈 배열입니다.
- `id`는 `type`(`movie`/`tv`)과 함께 써야 고유합니다. 포스터는 `posterPath` 앞에 TMDB 이미지 주소를 붙여 표시합니다.
- 로맨스·공포처럼 TV 장르 매핑이 없는 장르는 영화만 조회합니다. 기존 추천 규칙과 같습니다.
- 조회 기준은 추천과 같습니다 (한국어, 평가 수 200 이상, 성인물 제외).

## 오류

| 조건 | 상태 코드 | 오류 코드 |
| --- | --- | --- |
| 없는 장르 코드, 두 글자 ISO 코드가 아닌 국가 | 400 | VALIDATION_ERROR |
| 토큰 누락·형식 오류·만료·위조·다른 앱 또는 Access Token 사용 | 401 | UNAUTHORIZED |
| 사용자 상태가 DELETING 또는 DELETED | 401 | UNAUTHORIZED |
| TMDB 호출 실패 | 502 | UPSTREAM_ERROR |
| 공통 사용자 접근 검사 중 저장소 오류 | 500 | INTERNAL_ERROR |

오류 JSON은 기존 `{ "error": { "code": "...", "message": "..." } }` 형식을 따릅니다. 입력 오류는 TMDB를 부르기 전에 거절합니다.

## 캐시

- 장르·국가 조합(`ACTION|KR`, 필터 없음은 `ALL`)을 키로 결과를 **10분** 동안 보관합니다. 모든 사용자에게 같은 목록이므로 사용자 정보는 키에 넣지 않습니다.
- 보관 시간은 `recomate.popular.cache-minutes`(환경 변수 `RECOMATE_POPULAR_CACHE_MINUTES`)로 조정합니다. 조합은 최대 500개까지 보관합니다.
- 캐시는 Lambda 인스턴스 메모리에 있어 인스턴스끼리 공유하지 않습니다. 인스턴스가 새로 뜨면 첫 요청이 TMDB를 호출하고, 그 뒤 10분 동안은 호출하지 않습니다.
- TMDB 실패(502)는 저장하지 않으므로 다음 요청이 다시 시도합니다.

## 프론트 연결

- 로그인한 사용자: 메인 화면에서 장르·국가를 고를 때마다 이 API를 호출해 목록을 그립니다.
- 비회원: API를 호출하지 않고 블러 처리한 미리보기와 로그인 안내를 보여줍니다. 호출하면 401이 돌아옵니다.
- 401을 받으면 기존 `apiFetch`가 토큰을 한 번 갱신해 다시 요청한 뒤, 그래도 401이면 로그인 화면으로 보냅니다.

## 인프라와 검증

- `infra/user-api.yaml`: `/api/popular` 리소스, GET(Cognito Authorizer)·OPTIONS(인증 없음), 경로를 제한한 Lambda 호출 권한을 추가했습니다. 새 경로를 Stage에 반영하도록 배포 스냅샷을 `ApiDeploymentPopularV1`로 교체했습니다. Stage 호출 제한은 기존 기본값(초당 5회, 순간 10회)을 그대로 씁니다.
- 자동 테스트: `PopularApiTests`가 토큰 없음·탈퇴 사용자 401, 필터 없는 조회와 정렬, 필터 정규화, TV 매핑 없는 장르, 빈 필터, 잘못된 장르·국가 400, TMDB 실패 502와 비캐시를 확인합니다. `PopularServiceTest`가 캐시 적중, 조합별 분리, 10분 만료, 정렬·20개 제한을 확인합니다.
- 컨테이너 점검: `scripts/container-smoke.sh`가 토큰 없는 401과 사전 요청 200을 확인합니다. 컨테이너에는 TMDB 토큰이 없어 실제 목록은 배포 후 확인합니다.
- dev 배포(2026-11-11): 토큰 없는 401, 사전 요청 200, 로그인 토큰의 필터·무필터 200, 잘못된 장르·국가 400, 캐시 적중을 실제 호출로 확인했습니다. 상세 기록은 [컨테이너 배포](container-deployment.md#인기-목록-api-배포-2026-11-11)를 참고합니다.
- [배포 환경 자동 점검](live-check.md): 미인증 401·CORS 사전 요청, 잘못된 장르·국가 400, 필터·무필터 조회 200을 배포 때마다 확인합니다.
