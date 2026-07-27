package com.example.temperate.service.risk.ip2location.service;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.ip2location.domain.AcquiredIp2LocationKey;
import com.example.temperate.service.risk.ip2location.dto.Ip2LocationKeyBatchCommand;
import com.example.temperate.service.risk.ip2location.dto.Ip2LocationKeyBatchResult;
import com.example.temperate.service.risk.ip2location.dto.Ip2LocationKeyPage;
import java.util.List;
import java.util.Optional;

/**
 * 定义管理员凭据管理和供应商调用侧原子领取 IP2Location Key 的业务边界。
 */
public interface Ip2LocationApiKeyService {

    Ip2LocationKeyBatchResult importBatch(Ip2LocationKeyBatchCommand command);

    Ip2LocationKeyPage list(long cursor, int size);

    long delete(List<HmacIdentifier> keyIds);

    Optional<AcquiredIp2LocationKey> acquire();

    void discard(HmacIdentifier keyId);
}
