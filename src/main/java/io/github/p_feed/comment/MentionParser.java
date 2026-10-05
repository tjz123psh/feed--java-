package io.github.p_feed.comment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 提及解析：正则 @(\w+)
public final class MentionParser{

    private static final Pattern MENTION = Pattern.compile("@(\\w+)");

    private MentionParser(){
    }

    // 抽取被提及的用户名：按出现顺序去重，跳过自己
    public static List<String> extract(String content, String selfUsername){

        if(content == null){
            return List.of();
        }

        Matcher matcher = MENTION.matcher(content);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        while(matcher.find()){
            String name = matcher.group(1);
            if(selfUsername == null || !name.equals(selfUsername)){
                names.add(name);
            }
        }

        return new ArrayList<>(names);
    }
}
