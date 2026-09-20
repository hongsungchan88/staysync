package com.staysync.channel;

import com.staysync.shared.error.DomainException;

/**
 * 어댑터 종류가 반드시 쓰는 값이 비어 있다 — 자격 증명 키(Channex 의 {@code api_key}·
 * {@code property_id})나 매핑의 요금제 식별자.
 *
 * <p>서버는 자격 증명 키 목록을 강제하지 않고 받은 대로 저장한다({@code ChannelDtos}).
 * 다만 어댑터가 반드시 쓰는 값이 빠지면 연결은 만들어지고 첫 전송·수집에서야
 * {@code IllegalStateException} 으로 실패한다 — 그 실패는 워커 로그에만 남는다.
 * 만들 때 400 으로 돌려주는 편이 사람 손에 닿는다.
 */
public class MissingChannelFieldException extends DomainException {

    public MissingChannelFieldException(String field) {
        super("CHANNEL_FIELD_MISSING", "이 채널에는 " + field + " 가 필요합니다.");
    }
}
