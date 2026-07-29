package com.example.temperate.service.admin.mailinspection.recovery;

/**
 * 定期回收已经完整派发且不再对应活动任务的 Dispatch Marker 终态账本。
 */
public interface MailInspectionMarkerCleanupService {

    void cleanupTerminalMarkers();
}
