package com.lily.cicd.deploy;

/**
 * 이번 배포가 올릴 슬롯과, 트래픽을 옮긴 뒤에 거둘 슬롯.
 *
 * @param target   새 이미지를 올리는 슬롯. 성공 결과의 activeColor 가 된다
 * @param previous 전환 뒤에 거둘 슬롯. 아직 없으면 {@code retirePrevious} 가 아무 것도 하지 않는다
 */
public record SlotPlan(String target, String previous) {
}
