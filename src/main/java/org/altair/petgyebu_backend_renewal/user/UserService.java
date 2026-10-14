package org.altair.petgyebu_backend_renewal.user;

import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    // 인증 직후 행이 사라졌다면(동시 탈퇴) 종료된 로그인과 같이 취급한다.
    @Transactional(readOnly = true)
    public User me(long userId) {
        return users.findById(userId).orElseThrow(ApiException::unauthorized);
    }

    // 지출 → 월 예산 → 사용자 순으로 한 트랜잭션에서 삭제한다. 어느 단계든 실패하면 모두 롤백된다.
    @Transactional
    public void withdraw(long userId) {
        users.deleteExpensesOf(userId);
        users.deleteMonthlyBudgetsOf(userId);
        users.deleteUser(userId);
    }
}
