package com.flowzati.archone.stock.application.usecase;

import com.flowzati.archone.stock.application.command.CancelMovementsCommand;
import com.flowzati.archone.stock.application.movement.MovementCanceller;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CancelMovementsUsecaseTest {

  private MovementCanceller movementCanceller;
  private CancelMovementsUsecase usecase;

  @BeforeEach
  void setUp() {
    movementCanceller = mock(MovementCanceller.class);
    usecase = new CancelMovementsUsecase(movementCanceller);
  }

  @Test
  @DisplayName("應把這張單的搬運取消委派出去")
  void shouldDelegateTheWholeCancellation() {
    UUID orderId = UUID.randomUUID();

    usecase.execute(new CancelMovementsCommand(orderId));

    // 這支 usecase 把工作交出去；訊息冪等由 inbound decorator 負責。取消要碰哪些表、要濾掉
    // 什麼、要按什麼順序寫，全是「取消搬運」這個動作的內容——那些性質由 MovementCancellerTest 守著，
    // 在這裡重測一次只會讓同一條性質有兩個會一起壞掉的證人。
    verify(movementCanceller).cancelForOrder(orderId);
  }

}
