package com.yixin.interview.model.dto.question;

import com.yixin.interview.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;

/**
 * 查询题目请求
 *
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class QuestionQueryRequest extends PageRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * id
     */
    private Long notId;

    /**
     * 搜索词
     */
    private String searchText;

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
     * 创建用户 id
     */
    private Long userId;

    /**
     * 审核状态 0-待审核 1-通过 2-拒绝
     */
    private Integer reviewStatus;

    /**
     * 推荐答案
     */
    private String answer;

    /**
     * 图库 id
     */
    private Long questionBankId;

    private static final long serialVersionUID = 1L;
}