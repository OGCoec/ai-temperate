package com.example.temperate.service.user.membership.payment.callback;

/**
 * 该服务是来完成模拟六号回调业务字段校验并原子写入 Redis，不访问 PostgreSQL 或会员权益数据。
 */
public interface PaymentCallbackReceiveService {

    SimulatedLiuhaoCallbackResult receive(SimulatedLiuhaoCallbackCommand command);
}
