package com.junesoon.mim.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 加密媒体的保留期巡检。
 *
 * <p>为什么需要它：服务端拿不到密文里的媒体 id，无法判断某个密文是否还被
 * 某条消息引用，因此不能按「消息是否还存在」精确清理。两条经过论证的清理路径：
 *
 * <ol>
 *   <li><b>引用解除</b>：发送方本机知道媒体 id，清除会话或撤回时主动
 *       {@code DELETE /media/{id}}（接口只允许上传者删）；</li>
 *   <li><b>保留期</b>：即便引用方再也不上线，密文也不能无限期留在服务端。
 *       这里按 {@code crt_time} 定期清理超过保留期的密文。</li>
 * </ol>
 *
 * <p>清理后消息里的媒体引用会失效，客户端必须明确提示「附件已过期」，
 * 而不是把下载失败说成格式问题。保留期设 0 表示不清理（自建部署可关）。
 */
@Slf4j
@Component
public class MediaRetentionJob {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private final MediaService mediaService;

    /** 保留天数；≤0 表示关闭巡检。 */
    @Value("${mim.media.retention-days:30}")
    private int retentionDays;

    /** 单批处理条数：避免一次事务删太多把库压住。 */
    @Value("${mim.media.purge-batch:200}")
    private int batchSize;

    public MediaRetentionJob(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    /** 未完成的分片上传超过这个时间就视为废弃，连目录一起清掉。 */
    @Value("${mim.media.upload-ttl-hours:24}")
    private int uploadTtlHours;

    @Scheduled(cron = "${mim.media.purge-cron:0 30 3 * * ?}")
    public void purgeExpiredMedia() {
        if (retentionDays <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - retentionDays * DAY_MS;
        try {
            int removed = mediaService.purgeOlderThan(cutoff, batchSize);
            if (removed > 0) {
                log.info("媒体保留期巡检完成：保留 {} 天，本次清理 {} 个密文", retentionDays, removed);
            }
        } catch (RuntimeException e) {
            // 巡检失败不能影响主流程，下一轮会重试。
            log.warn("媒体保留期巡检失败：{}", e.getMessage());
        }
        // 半成品分片同样不能无限期占磁盘：按「最后活跃时间」清。
        if (uploadTtlHours > 0) {
            long uploadCutoff = System.currentTimeMillis() - uploadTtlHours * 60L * 60L * 1000L;
            try {
                int stale = mediaService.purgeStaleUploads(uploadCutoff, batchSize);
                if (stale > 0) {
                    log.info("分片上传巡检完成：清理 {} 个超过 {} 小时未完成的上传",
                        stale, uploadTtlHours);
                }
            } catch (RuntimeException e) {
                log.warn("分片上传巡检失败：{}", e.getMessage());
            }
        }
    }
}
