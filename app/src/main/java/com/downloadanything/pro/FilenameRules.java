package com.downloadanything.pro;

import java.util.Locale;

/** Conservative filename rules: preserve titles, add stable batch order, and remove only illegal filesystem characters. */
public final class FilenameRules {
    private FilenameRules(){}
    public static String outputTemplate(DownloadJob j){
        String prefix=batchPrefix(j);
        int titleBytes=prefix.isEmpty()?180:150;
        return prefix+"%(title)."+titleBytes+"B [%(id)s].%(ext)s";
    }
    public static String batchPrefix(DownloadJob j){
        if(j==null||j.batchSize<=0||j.batchIndex<=0)return "";
        int width=Math.max(2,Integer.toString(Math.max(j.batchSize,j.batchIndex)).length());
        return String.format(Locale.US,"%0"+width+"d - ",j.batchIndex);
    }
    public static String safeBase(String s){
        if(s==null)return "";
        String x=s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]"," ").replaceAll("\\s+"," ").trim();
        while(x.endsWith(".")||x.endsWith(" "))x=x.substring(0,x.length()-1).trim();
        if(x.length()>96)x=x.substring(0,96).trim();
        return x;
    }
}
