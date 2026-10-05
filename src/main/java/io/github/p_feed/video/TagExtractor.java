package io.github.p_feed.video;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TagExtractor{

    private static final Pattern TAG_PATTERN = Pattern.compile("#([\\p{L}\\p{N}_]+)");

    private TagExtractor(){
    }

    // title + " " + description 中抽取 #话题，按出现顺序去重
    public static List<String> extract(String title, String description){
        Matcher matcher = TAG_PATTERN.matcher(title + " " + description);
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        while(matcher.find()){
            tags.add(matcher.group(1));
        }

        return new ArrayList<>(tags);
    }
}
