package com.hjs.study.ragent.framework.convention;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class ChatMessage {

    public enum Role{

        SYSTEM,
        USER,
        ASSISTANT;

        public static Role formString(String value){
            for(Role role : Role.values()){
                if (role.name().equals(value)){
                    return role;
                }
            }
            throw new IllegalArgumentException("无效的角色类型："+value);
        }
    }


    private Role role;

    private String content;

    private String thinkingContent;

    private Integer thinkDuration;

    public ChatMessage(Role role, String content){
        this.role = role;
        this.content = content;
    }

    public static ChatMessage system(String content){
        return new ChatMessage(Role.SYSTEM, content);
    }

    public static ChatMessage user(String content){
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage assistant(String content){
        return new ChatMessage(Role.ASSISTANT, content);
    }

    public static ChatMessage assistant(String content, String thinkingContent, Integer thinkDuration){
        ChatMessage message = new ChatMessage(Role.ASSISTANT, content);
        message.thinkingContent = thinkingContent;
        message.thinkDuration = thinkDuration;
        return message;
    }

    public static ChatMessage assistant(String content, String thinkingContent){
        return assistant(content, thinkingContent, null);
    }
}
