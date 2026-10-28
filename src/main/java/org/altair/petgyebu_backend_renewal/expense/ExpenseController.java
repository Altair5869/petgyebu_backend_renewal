package org.altair.petgyebu_backend_renewal.expense;

import java.time.LocalDate;
import java.util.List;
import org.altair.petgyebu_backend_renewal.auth.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
public class ExpenseController {

    record ExpenseResponse(long id, long amount, LocalDate expenseDate, Category category, String memo) {

        static ExpenseResponse of(Expense e) {
            return new ExpenseResponse(e.id(), e.amount(), e.expenseDate(), e.category(), e.memo());
        }
    }

    record ExpensePageResponse(List<ExpenseResponse> items, String nextCursor) {
    }

    private final ExpenseService expenseService;

    public ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    // Idempotency-Key가 있고 같은 요청이 이미 저장됐으면 200과 기존 지출, 새로 만들면 201이다.
    @PostMapping("/expenses")
    ResponseEntity<ExpenseResponse> create(@AuthenticationPrincipal AuthUser authUser,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        ExpenseService.CreateResult result = expenseService.create(authUser.userId(), body, idempotencyKey);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ExpenseResponse.of(result.expense()));
    }

    @GetMapping("/months/{month}/expenses")
    ExpensePageResponse list(@AuthenticationPrincipal AuthUser authUser, @PathVariable String month,
            @RequestParam(defaultValue = "" + ExpenseService.DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) String category) {
        ExpenseService.Page page = expenseService.list(authUser.userId(), month, limit, cursor, category);
        return new ExpensePageResponse(page.items().stream().map(ExpenseResponse::of).toList(), page.nextCursor());
    }

    @GetMapping("/expenses/{expenseId}")
    ExpenseResponse get(@AuthenticationPrincipal AuthUser authUser, @PathVariable long expenseId) {
        return ExpenseResponse.of(expenseService.get(authUser.userId(), expenseId));
    }

    @PatchMapping("/expenses/{expenseId}")
    ExpenseResponse update(@AuthenticationPrincipal AuthUser authUser, @PathVariable long expenseId,
            @RequestBody JsonNode body) {
        return ExpenseResponse.of(expenseService.update(authUser.userId(), expenseId, body));
    }

    @DeleteMapping("/expenses/{expenseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AuthUser authUser, @PathVariable long expenseId) {
        expenseService.delete(authUser.userId(), expenseId);
    }
}
