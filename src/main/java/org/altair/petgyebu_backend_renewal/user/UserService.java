package org.altair.petgyebu_backend_renewal.user;

import java.util.Set;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.altair.petgyebu_backend_renewal.common.DbErrors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@Service
public class UserService {

    private static final Set<String> PET_TYPES = Set.of("CAT", "DOG");
    private static final int WITHDRAW_ATTEMPTS = 3;

    private final UserRepository users;
    private final TransactionTemplate tx;

    public UserService(UserRepository users, TransactionTemplate tx) {
        this.users = users;
        this.tx = tx;
    }

    // 인증 직후 행이 사라졌다면(동시 탈퇴) 종료된 로그인과 같이 취급한다.
    @Transactional(readOnly = true)
    public User me(long userId) {
        return users.findById(userId).orElseThrow(ApiException::unauthorized);
    }

    // 지출 → 월 예산 → 사용자 순으로 한 트랜잭션에서 삭제한다. 어느 단계든 실패하면 모두 롤백된다.
    // 삭제 도중 같은 사용자의 지출·예산이 끼어들어 FK 위반이 나면 새 트랜잭션으로 처음부터 다시 시도한다.
    public void withdraw(long userId) {
        for (int attempt = 1; ; attempt++) {
            try {
                tx.executeWithoutResult(status -> {
                    users.deleteExpensesOf(userId);
                    users.deleteMonthlyBudgetsOf(userId);
                    users.deleteUser(userId);
                });
                return;
            } catch (DataIntegrityViolationException e) {
                if (attempt >= WITHDRAW_ATTEMPTS || !DbErrors.isForeignKeyViolation(e)) {
                    throw e;
                }
            }
        }
    }

    // users.pet_type만 바꾼다. 지출·예산은 건드리지 않는다. 대소문자를 구분하고 null로 되돌리지 않는다.
    @Transactional
    public User selectPet(long userId, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 본문을 읽을 수 없습니다.", null);
        }
        JsonNode petType = body.get("petType");
        if (petType == null || !petType.isString() || !PET_TYPES.contains(petType.stringValue())) {
            throw ApiException.invalidRequest("petType", "CAT 또는 DOG만 허용합니다.");
        }
        if (users.updatePetType(userId, petType.stringValue()) == 0) {
            throw ApiException.unauthorized();
        }
        return me(userId);
    }
}
