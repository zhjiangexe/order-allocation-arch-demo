package com.flowzati.archone.inventory.allocation.infrastructure.aop;

import com.flowzati.archone.foundation.error.StaleStateException;
import com.flowzati.archone.inventory.allocation.application.error.StockAllocationErrorCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/** Translates application-level stale planning into the messaging retry protocol at the infrastructure edge. */
@Aspect
@Component
public class AssignmentRetryConflictTranslator {

    @Around("execution(* com.flowzati.archone.inventory.allocation.application.service."
            + "StockOperationAssignmentCoordinator.tryAssign*(..))")
    public Object translate(ProceedingJoinPoint invocation) throws Throwable {
        try {
            return invocation.proceed();
        } catch (StaleStateException staleProposal) {
            if (staleProposal.errorCode() != StockAllocationErrorCode.STOCK_ALLOCATION_PROPOSAL_STALE) {
                throw staleProposal;
            }
            throw new OptimisticLockingFailureException(staleProposal.getMessage(), staleProposal);
        }
    }
}
