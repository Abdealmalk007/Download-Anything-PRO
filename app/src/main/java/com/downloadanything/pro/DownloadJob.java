package com.downloadanything.pro;

import org.json.JSONObject;

public class DownloadJob {
    public String id="", url="", originalUrl="", resolvedUrl="", requestId="", formats="", sourceId="", title="", thumbnail="", platform="", duration="", expectedSize="";
    public String quality="medium", audioQuality="best", audioFormat="m4a", videoContainer="mp4", subtitleMode="none";
    /** Optional exact source format selection from the 4.7.5 bottom-sheet picker. */
    public String selectedVideoFormatId="", selectedAudioFormatId="", selectedVideoExt="";
    public boolean selectedVideoFormatHasAudio=false;
    public String cookieFile="", userAgent="", state="queued", speed="", phase="queued", outputUri="", lastError="";
    public boolean audio=false, turbo=true, embedMetadata=false, embedThumbnail=false, quickShare=false;
    /** Set only after real source analysis; permits genuinely silent source videos. */
    public boolean sourceAudioKnown=false, sourceHasAudio=true;
    public int progress=0, networkRetryCount=0, engineRetryCount=0, compatibilityRetryCount=0, expiredUrlRetryCount=0, smartRetryCount=0, recoveryCount=0;
    public long eta=0L, downloadedBytes=0L, totalBytes=0L, createdAt=System.currentTimeMillis(), updatedAt=System.currentTimeMillis(), startedAt=0L, analyzedAt=0L, lastRecoveryAt=0L, nextRetryAt=0L;
    public String retryKind="";
    public String batchId="", batchTitle="", ruleProfile="", rulePlatform="", strategyToken="", strategyTrace="";
    public int batchIndex=0, batchSize=0, strategyAttempt=0;

    public JSONObject toJson() {
        JSONObject o=new JSONObject();
        try {
            o.put("id",id); o.put("url",url); o.put("originalUrl",originalUrl); o.put("sourceId",sourceId); o.put("resolvedUrl",resolvedUrl); o.put("requestId",requestId); o.put("formats",formats); o.put("title",title); o.put("thumbnail",thumbnail); o.put("platform",platform);
            o.put("duration",duration); o.put("expectedSize",expectedSize); o.put("quality",quality); o.put("audioQuality",audioQuality); o.put("audioFormat",audioFormat);
            o.put("videoContainer",videoContainer); o.put("subtitleMode",subtitleMode);
            o.put("selectedVideoFormatId",selectedVideoFormatId); o.put("selectedAudioFormatId",selectedAudioFormatId); o.put("selectedVideoExt",selectedVideoExt); o.put("selectedVideoFormatHasAudio",selectedVideoFormatHasAudio); o.put("cookieFile",cookieFile);
            o.put("userAgent",userAgent); o.put("state",state); o.put("speed",speed); o.put("phase",phase); o.put("outputUri",outputUri);
            o.put("lastError",lastError); o.put("batchId",batchId); o.put("batchTitle",batchTitle); o.put("ruleProfile",ruleProfile); o.put("rulePlatform",rulePlatform); o.put("strategyToken",strategyToken); o.put("strategyTrace",strategyTrace); o.put("batchIndex",batchIndex); o.put("batchSize",batchSize); o.put("strategyAttempt",strategyAttempt); o.put("audio",audio); o.put("turbo",turbo); o.put("embedMetadata",embedMetadata);
            o.put("embedThumbnail",embedThumbnail); o.put("quickShare",quickShare); o.put("sourceAudioKnown",sourceAudioKnown); o.put("sourceHasAudio",sourceHasAudio); o.put("progress",progress); o.put("networkRetryCount",networkRetryCount); o.put("engineRetryCount",engineRetryCount); o.put("compatibilityRetryCount",compatibilityRetryCount); o.put("expiredUrlRetryCount",expiredUrlRetryCount); o.put("smartRetryCount",smartRetryCount); o.put("recoveryCount",recoveryCount); o.put("retryKind",retryKind); o.put("nextRetryAt",nextRetryAt); o.put("lastRecoveryAt",lastRecoveryAt); o.put("eta",eta); o.put("createdAt",createdAt);
            o.put("downloadedBytes",downloadedBytes); o.put("totalBytes",totalBytes); o.put("updatedAt",updatedAt); o.put("startedAt",startedAt); o.put("analyzedAt",analyzedAt);
        } catch (Exception ignored) {}
        return o;
    }

    public static DownloadJob fromJson(JSONObject o) {
        DownloadJob j=new DownloadJob();
        if (o==null) return j;
        j.id=o.optString("id",""); j.url=o.optString("url",""); j.originalUrl=o.optString("originalUrl",j.url); j.sourceId=o.optString("sourceId",""); j.resolvedUrl=o.optString("resolvedUrl",j.url); j.requestId=o.optString("requestId",j.id); j.formats=o.optString("formats",""); j.title=o.optString("title","");
        j.thumbnail=o.optString("thumbnail",""); j.platform=o.optString("platform",""); j.duration=o.optString("duration","");
        j.expectedSize=o.optString("expectedSize",""); j.quality=o.optString("quality","medium");
        j.audioQuality=o.optString("audioQuality","best"); j.audioFormat=o.optString("audioFormat","m4a"); j.videoContainer=o.optString("videoContainer","mp4");
        j.subtitleMode=o.optString("subtitleMode","none"); j.selectedVideoFormatId=o.optString("selectedVideoFormatId",""); j.selectedAudioFormatId=o.optString("selectedAudioFormatId",""); j.selectedVideoExt=o.optString("selectedVideoExt",""); j.selectedVideoFormatHasAudio=o.optBoolean("selectedVideoFormatHasAudio",false); j.cookieFile=o.optString("cookieFile","");
        j.userAgent=o.optString("userAgent",""); j.state=o.optString("state","queued"); j.speed=o.optString("speed",""); j.phase=o.optString("phase",j.state);
        j.outputUri=o.optString("outputUri",""); j.lastError=o.optString("lastError",""); j.batchId=o.optString("batchId",""); j.batchTitle=o.optString("batchTitle",""); j.ruleProfile=o.optString("ruleProfile",""); j.rulePlatform=o.optString("rulePlatform",""); j.strategyToken=o.optString("strategyToken",""); j.strategyTrace=o.optString("strategyTrace",""); j.batchIndex=o.optInt("batchIndex",0); j.batchSize=o.optInt("batchSize",0); j.strategyAttempt=o.optInt("strategyAttempt",0);
        j.audio=o.optBoolean("audio",false); j.turbo=o.optBoolean("turbo",true); j.sourceAudioKnown=o.optBoolean("sourceAudioKnown",false); j.sourceHasAudio=o.optBoolean("sourceHasAudio",true);
        j.embedMetadata=o.optBoolean("embedMetadata",false); j.embedThumbnail=o.optBoolean("embedThumbnail",false); j.quickShare=o.optBoolean("quickShare",false);
        j.progress=o.optInt("progress",0); j.networkRetryCount=o.optInt("networkRetryCount",0); j.engineRetryCount=o.optInt("engineRetryCount",0); j.compatibilityRetryCount=o.optInt("compatibilityRetryCount",0); j.expiredUrlRetryCount=o.optInt("expiredUrlRetryCount",0); j.smartRetryCount=o.optInt("smartRetryCount",0); j.recoveryCount=o.optInt("recoveryCount",0); j.retryKind=o.optString("retryKind",""); j.nextRetryAt=o.optLong("nextRetryAt",0L); j.lastRecoveryAt=o.optLong("lastRecoveryAt",0L); j.eta=o.optLong("eta",0L); j.createdAt=o.optLong("createdAt",System.currentTimeMillis());
        j.downloadedBytes=o.optLong("downloadedBytes",0L); j.totalBytes=o.optLong("totalBytes",0L); j.updatedAt=o.optLong("updatedAt",j.createdAt); j.startedAt=o.optLong("startedAt",0L); j.analyzedAt=o.optLong("analyzedAt",0L);
        return j;
    }

    public DownloadJob copy() {
        DownloadJob j=new DownloadJob();
        j.id=id; j.url=url; j.originalUrl=originalUrl; j.sourceId=sourceId; j.resolvedUrl=resolvedUrl; j.requestId=requestId; j.formats=formats; j.title=title; j.thumbnail=thumbnail; j.platform=platform; j.duration=duration; j.expectedSize=expectedSize;
        j.quality=quality; j.audioQuality=audioQuality; j.audioFormat=audioFormat; j.videoContainer=videoContainer; j.subtitleMode=subtitleMode;
        j.selectedVideoFormatId=selectedVideoFormatId; j.selectedAudioFormatId=selectedAudioFormatId; j.selectedVideoExt=selectedVideoExt; j.selectedVideoFormatHasAudio=selectedVideoFormatHasAudio;
        j.cookieFile=cookieFile; j.userAgent=userAgent; j.state=state; j.speed=speed; j.phase=phase; j.outputUri=outputUri; j.lastError=lastError;
        j.audio=audio; j.turbo=turbo; j.embedMetadata=embedMetadata; j.embedThumbnail=embedThumbnail; j.quickShare=quickShare; j.sourceAudioKnown=sourceAudioKnown; j.sourceHasAudio=sourceHasAudio;
        j.batchId=batchId; j.batchTitle=batchTitle; j.ruleProfile=ruleProfile; j.rulePlatform=rulePlatform; j.strategyToken=strategyToken; j.strategyTrace=strategyTrace; j.batchIndex=batchIndex; j.batchSize=batchSize; j.strategyAttempt=strategyAttempt;
        j.progress=progress; j.networkRetryCount=networkRetryCount; j.engineRetryCount=engineRetryCount; j.compatibilityRetryCount=compatibilityRetryCount; j.expiredUrlRetryCount=expiredUrlRetryCount; j.smartRetryCount=smartRetryCount; j.recoveryCount=recoveryCount; j.retryKind=retryKind; j.nextRetryAt=nextRetryAt; j.lastRecoveryAt=lastRecoveryAt;
        j.eta=eta; j.downloadedBytes=downloadedBytes; j.totalBytes=totalBytes; j.createdAt=createdAt; j.updatedAt=updatedAt; j.startedAt=startedAt; j.analyzedAt=analyzedAt;
        return j;
    }

    public boolean isActiveLike() {
        return "queued".equals(state)||"downloading".equals(state)||"resuming".equals(state)||"paused".equals(state)||"waiting_retry".equals(state)||"waiting_engine".equals(state)||"waiting_network".equals(state);
    }
}
