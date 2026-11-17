# 작품 상세 API

추천 결과·인기 목록·본 작품 목록의 작품 카드를 눌렀을 때 보여줄 영화·TV 상세 정보를 TMDB에서 조회해 돌려줍니다. 카드에 이미 있는 `mediaType`(`movie`/`tv`)과 `tmdbId`(인기 목록은 `type`/`id`)로 호출합니다. **로그인(Cognito ID Token)이 필요합니다.** 전체 스키마는 [OpenAPI](openapi.yaml)의 `getContentDetail`을 따르며, 구현 이슈는 [#38](https://github.com/leee3kgp/recomate-backend/issues/38)입니다.

> **비회원:** 팀 결정(2026-11-17)에 따라 로그인 전용입니다. 비회원이 작품 카드를 누르면 프론트가 이 API를 호출하지 않고 "로그인이 필요합니다" 안내와 로그인·회원가입 버튼을 보여줍니다(홈 화면 인기 목록의 비회원 안내와 같은 방식). 토큰 없이 호출하면 401입니다.

## 경로

| 메서드·경로 | 설명 | 성공 |
| --- | --- | --- |
| `GET /api/contents/{mediaType}/{tmdbId}` | 작품 한 편의 상세 정보 | 200 |

## 응답

```json
{
  "tmdbId": 603,
  "mediaType": "movie",
  "title": "매트릭스",
  "originalTitle": "The Matrix",
  "overview": "모피어스는 네오에게 ...",
  "posterPath": "/p96dm7sCMn4VYAStA6siNz30G1r.jpg",
  "backdropPath": "/tlm8UkiQsitc8rSuIAscQDCnP8d.jpg",
  "releaseDate": "1999-03-31",
  "releaseYear": 1999,
  "voteAverage": 8.2,
  "voteCount": 26000,
  "genres": ["액션", "SF"],
  "runtime": 136,
  "creators": ["Lana Wachowski", "Lilly Wachowski"],
  "cast": [
    { "name": "키아누 리브스", "character": "Neo", "profilePath": "/4D0PpNI0kmP58hgrwGC3wCjxhnm.jpg" }
  ],
  "providers": [
    { "id": 8, "name": "Netflix", "logoPath": "/pbpMk2JmcoNnQwx5JGpXngfoWtp.jpg" }
  ],
  "watched": false
}
```

| 필드 | 설명 |
| --- | --- |
| `title`, `originalTitle` | 한국어 제목과 원제. 원제가 없으면 빠집니다. |
| `overview` | 한국어 줄거리. TMDB에 한국어 줄거리가 없으면 빠집니다. |
| `posterPath`, `backdropPath` | TMDB 이미지 경로. `https://image.tmdb.org/t/p/w500` 같은 크기 접두사를 붙여 씁니다. |
| `releaseDate`, `releaseYear` | 영화는 개봉일, TV는 첫 방영일 기준입니다. |
| `genres` | TMDB 한국어 장르 이름 그대로입니다. 화면 표시용이며 RecoMate 장르 코드(`ACTION` 등)와 다릅니다. |
| `runtime` | 분 단위. 영화는 전체 상영 시간, TV는 회당 시간입니다. |
| `seasons`, `episodes` | TV에만 있는 시즌 수·전체 회차 수입니다. |
| `creators` | 영화는 감독, TV는 제작자(created by)이며 최대 3명입니다. |
| `cast` | 주요 출연진을 크레딧 순서대로 최대 5명. `character`(배역)·`profilePath`는 없으면 빠집니다. |
| `providers` | 한국에서 **구독형**으로 볼 수 있는 OTT(넷플릭스·왓챠 등)입니다. 대여·구매는 포함하지 않으며, 없으면 빈 배열입니다. 정보 출처는 TMDB(JustWatch 제공)입니다. |
| `watched` | 요청한 사용자가 이 작품을 본 작품에 저장했는지입니다(항상 있음). 본 작품 저장·삭제는 [본 작품 API](watched-api.md)를 씁니다. |

값이 없는 필드는 `null` 대신 응답에서 빠지고, 목록 필드(`genres`, `creators`, `cast`, `providers`)는 빈 배열입니다.

## 오류

| 조건 | 상태 코드 | 오류 코드 |
| --- | --- | --- |
| `mediaType`이 `movie`/`tv`가 아님, `tmdbId`가 양의 정수가 아님 | 400 | VALIDATION_ERROR |
| 토큰 문제, 탈퇴 중·탈퇴한 사용자 | 401 | UNAUTHORIZED |
| TMDB에 없는 작품(성인물 포함) | 404 | NOT_FOUND |
| TMDB 호출 실패 | 502 | UPSTREAM_ERROR |

## 동작

- **TMDB 한 번 호출:** 기본 정보·출연진·OTT 제공처를 `append_to_response=credits,watch/providers`로 한 번에 받아 Lambda 제한 시간 안에 응답합니다.
- **캐시:** 작품 정보는 누구에게나 같으므로 `mediaType:tmdbId`를 키로 인스턴스 메모리에 30분(`recomate.content.cache-minutes`) 보관합니다. 404·502는 저장하지 않아 다음 요청이 다시 조회합니다. 사용자마다 다른 `watched`는 캐시하지 않고 요청마다 본 작품 테이블을 조회합니다.
- **응답 캐시 금지:** `watched`가 사용자마다 다르므로 `Cache-Control: no-store`로 응답합니다.
- 본 작품 여부 조회는 본 작품 테이블의 기존 `Query` 권한을 사용하므로 새 IAM 권한이 필요 없습니다.

## 인프라

- `infra/user-api.yaml`: `/api/contents/{mediaType}/{tmdbId}` 경로(GET은 Cognito Authorizer, OPTIONS는 인증 없음), 이 경로로 한정한 Lambda 호출 권한 `ContentDetailInvokePermission`, 배포 스냅샷 `ApiDeploymentContentDetailV1`을 추가했습니다.
- 새 테이블·환경 변수·IAM 권한은 없습니다. TMDB 토큰은 기존 값을 쓰고, 본 작품 여부는 기존 본 작품 테이블 `Query` 권한으로 조회합니다.

## 검증

- 자동 테스트: `ContentDetailApiTests`(인증, 응답 필드, `watched`, 캐시 적중과 `watched` 매번 조회, 영화·TV 구분, 404·502 미캐시, 입력 검사, 탈퇴 사용자), `RestClientTmdbClientDetailTest`(로컬 HTTP 서버로 실제 TMDB 클라이언트의 단일 호출·영화/TV 필드 변환·출연진 순서와 개수·한국 구독형 OTT만 추출·404·성인물·502), `DynamoDbLocalTests`(본 작품 한 편 조회).
- dev 배포(2026-11-17, 이미지 `src-737368404129a649`, 배포 ID `jgo2is`): 배포 환경 자동 점검 121개를 모두 통과했습니다. 상세 기록은 [컨테이너 배포](container-deployment.md#작품-상세-api-배포-2026-11-17)를 참고합니다.
- [배포 환경 자동 점검](live-check.md): 새 경로의 401·CORS, 입력 검사 400, TV 작품 상세 필드와 본 작품 표시, 없는 작품 404, 본 작품 삭제 후 `watched` false를 확인합니다.
