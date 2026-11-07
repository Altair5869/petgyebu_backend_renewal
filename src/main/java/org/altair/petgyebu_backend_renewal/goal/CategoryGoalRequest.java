package org.altair.petgyebu_backend_renewal.goal;

import org.altair.petgyebu_backend_renewal.common.Amount;

// 카테고리 목표 설정 본문.
record CategoryGoalRequest(@Amount Long amount) {
}
