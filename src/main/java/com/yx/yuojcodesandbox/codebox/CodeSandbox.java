package com.yx.yuojcodesandbox.codebox;


import com.yx.yuojcodesandbox.model.ExecuteCodeRequest;
import com.yx.yuojcodesandbox.model.ExecuteCodeResponse;

/**
 * 代码沙箱接口
 */
public interface CodeSandbox {
    /**
     * 执行代码
     *
     * @param request 判题参数
     * @return 判题结果
     */
     ExecuteCodeResponse executeCode(ExecuteCodeRequest request);
}
