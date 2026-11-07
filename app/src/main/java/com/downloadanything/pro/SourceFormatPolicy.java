package com.downloadanything.pro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 4.8.6 Phase 2 policy for presenting only source-reported formats.
 * It never manufactures a resolution/bitrate that was not returned by the extractor.
 */
public final class SourceFormatPolicy {
    private SourceFormatPolicy() {}

    public static boolean usableVideo(MediaFormatOption o) {
        return o != null && o.video && !o.id.isEmpty() && o.height > 0;
    }

    public static boolean usableAudio(MediaFormatOption o) {
        return o != null && o.audio && !o.video && !o.id.isEmpty();
    }

    /** One strongest source-reported format per real resolution, highest first. */
    public static ArrayList<MediaFormatOption> primaryVideo(List<MediaFormatOption> input) {
        ArrayList<MediaFormatOption> src = new ArrayList<>();
        if (input != null) for (MediaFormatOption o : input) if (usableVideo(o)) src.add(o);
        src.sort((a,b) -> {
            int h = Integer.compare(b.height, a.height);
            if (h != 0) return h;
            return Integer.compare(score(b), score(a));
        });
        LinkedHashMap<Integer,MediaFormatOption> best = new LinkedHashMap<>();
        for (MediaFormatOption o : src) {
            MediaFormatOption old = best.get(o.height);
            if (old == null || score(o) > score(old)) best.put(o.height,o);
        }
        return new ArrayList<>(best.values());
    }

    /** Source-reported audio-only streams, strongest bitrate first. */
    public static ArrayList<MediaFormatOption> primaryAudio(List<MediaFormatOption> input) {
        ArrayList<MediaFormatOption> src = new ArrayList<>();
        if (input != null) for (MediaFormatOption o : input) if (usableAudio(o)) src.add(o);
        src.sort((a,b) -> {
            int r = Double.compare(b.abr,a.abr);
            if (r != 0) return r;
            return Boolean.compare("m4a".equalsIgnoreCase(b.ext),"m4a".equalsIgnoreCase(a.ext));
        });
        LinkedHashMap<Integer,MediaFormatOption> best = new LinkedHashMap<>();
        int unknown=1;
        for (MediaFormatOption o : src) {
            int bucket=o.abr>0?(int)Math.round(o.abr):-unknown++;
            if(!best.containsKey(bucket))best.put(bucket,o);
        }
        return new ArrayList<>(best.values());
    }

    /** Medium = exact 720p, otherwise nearest lower; only then smallest higher real source format. */
    public static MediaFormatOption mediumVideo(List<MediaFormatOption> input) {
        ArrayList<MediaFormatOption> formats=primaryVideo(input);
        if(formats.isEmpty())return null;
        MediaFormatOption lower=null,higher=null;
        for(MediaFormatOption o:formats){
            if(o.height==VideoQualityPolicy.MEDIUM_TARGET_HEIGHT)return o;
            if(o.height<VideoQualityPolicy.MEDIUM_TARGET_HEIGHT && (lower==null||o.height>lower.height))lower=o;
            if(o.height>VideoQualityPolicy.MEDIUM_TARGET_HEIGHT && (higher==null||o.height<higher.height))higher=o;
        }
        return lower!=null?lower:higher;
    }

    public static MediaFormatOption bestAudio(List<MediaFormatOption> input) {
        ArrayList<MediaFormatOption> a=primaryAudio(input);
        return a.isEmpty()?null:a.get(0);
    }

    public static int score(MediaFormatOption o){
        if(o==null)return Integer.MIN_VALUE;
        int score=o.progressive()?100000:0;
        if("mp4".equalsIgnoreCase(o.ext))score+=50000;
        score+=(int)Math.min(20000,Math.round(o.tbr));
        return score;
    }
}
