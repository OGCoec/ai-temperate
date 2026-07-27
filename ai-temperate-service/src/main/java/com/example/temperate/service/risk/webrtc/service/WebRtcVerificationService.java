package com.example.temperate.service.risk.webrtc.service;

import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationDecision;
import java.util.List;

/**
 * 定义 PreAuth WebRTC 状态读取与报告校验边界，最终结论始终由可信 HTTP IP 计算。
 */
public interface WebRtcVerificationService {

    WebRtcVerificationDecision inspect(
            PreAuthAccess access,
            String currentHttpIp);

    WebRtcVerificationDecision report(
            PreAuthAccess access,
            String currentHttpIp,
            List<String> reportedWebRtcIps);
}
