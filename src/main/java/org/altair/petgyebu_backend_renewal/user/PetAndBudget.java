package org.altair.petgyebu_backend_renewal.user;

// 홈에 쓰는 사용자의 펫 종류와 한 달 예산 금액. 둘 다 아직 정하지 않았으면 null이다.
public record PetAndBudget(String petType, Long budgetAmount) {
}
