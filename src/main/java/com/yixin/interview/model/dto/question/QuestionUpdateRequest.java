package com.yixin.interview.model.dto.question;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 更新题目请求
 *
 */
@Data
public class QuestionUpdateRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 标题
     */
    private String title;

    /**
     * 内容
     */
    private String content;

    /**
     * 标签列表
     */
    private List<String> tags;

    /**
     * 审核状态 0-待审核 1-通过 2-拒绝
     */
    private Integer reviewStatus;

    /**
     * 推荐答案
     */
    private String answer;


    private static final long serialVersionUID = 1L;
}