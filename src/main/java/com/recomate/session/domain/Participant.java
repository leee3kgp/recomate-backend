package com.recomate.session.domain;

import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

/** 세션 참여자 (오프라인 경험형: 계정 연동 없이 index + 장르만 저장). */
@DynamoDbBean
public class Participant {
    private Integer index;
    private List<String> genres = new ArrayList<>();

    public Participant() {}

    public Participant(Integer index, List<String> genres) {
        this.index = index;
        this.genres = genres;
    }

    public Integer getIndex() { return index; }
    public void setIndex(Integer index) { this.index = index; }
    public List<String> getGenres() { return genres; }
    public void setGenres(List<String> genres) { this.genres = genres; }
}
