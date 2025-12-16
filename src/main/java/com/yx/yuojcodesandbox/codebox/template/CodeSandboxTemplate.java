package com.yx.yuojcodesandbox.codebox.template;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.FoundWord;
import cn.hutool.dfa.WordTree;
import com.yx.yuojcodesandbox.codebox.CodeSandbox;
import com.yx.yuojcodesandbox.model.ExecuteCodeRequest;
import com.yx.yuojcodesandbox.model.ExecuteCodeResponse;
import com.yx.yuojcodesandbox.model.ExecuteMessage;
import com.yx.yuojcodesandbox.model.JudgeInfo;
import com.yx.yuojcodesandbox.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
public abstract class CodeSandboxTemplate implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    // 超时时间
    private static final long TIME_OUT = 5000L;

    //操作黑名单
    private static final List<String> BLACK_LIST = Arrays.asList("exec", "File");

    // 操作黑名单字典树
    private static final WordTree BLACK_WORDTREE = new WordTree();
    static {
        //初始化字典树
        BLACK_WORDTREE.addWords(BLACK_LIST);
    }

    /**
     * 整体执行流程与步骤实现
     *
     * @param request 判题参数
     * @return
     */
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest request) {
        String code = request.getCode();
        String language = request.getLanguage();
        List<String> inputList = request.getInputList();
        ExecuteCodeResponse executeCodeResponse = null;
        try {
//      1.保存代码文件
            File userCodeFile = saveCodeToFile(code);

//      2.编译代码
            ExecuteMessage executeMessage = compileFile(userCodeFile);

//      3.运行代码
            List<ExecuteMessage> executeMessageList = runFile(inputList,userCodeFile);

//      4.整理输出
            executeCodeResponse = getOutputResponse(executeMessageList);

//      5.文件清理
            boolean b = deleteFile(userCodeFile);
            if (!b) {
                log.error("文件清理失败，文件路径："+userCodeFile.getAbsolutePath());
            }
            return executeCodeResponse;
        } catch (Exception e) {
            return getErrorResponse(e);
        }
    }


    /**
     * 1.保存代码文件
     * @param code
     * @return
     */
    public File saveCodeToFile(String code){
        //  使用字典树校验代码中是否包含黑名单中的禁用词
        FoundWord foundWord = BLACK_WORDTREE.matchWord(code);
        if (foundWord != null) {
            System.out.println("包含禁止词：" + foundWord.getFoundWord());
            return null;
        }

        // 获取当前项目的路径
        String UserDir = System.getProperty("user.dir");
        // 拼接文件夹路径
        String globalCodePathName = UserDir + File.separator + GLOBAL_CODE_DIR_NAME;
        //判断全局代码目录是否存在，不存在则创建
        if (!FileUtil.exist(globalCodePathName)) {
            FileUtil.mkdir(globalCodePathName);
        }
        //把用户代码进行隔离：每一个代码创建一个临时目录
        String userCodeParentPath = globalCodePathName + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParentPath + File.separator + GLOBAL_JAVA_CLASS_NAME;
        //将用户的代码存到文件中 （HuTool工具写入）
        File userCodeFile = FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);
        return userCodeFile;
    }

    /**
     * 2.编译代码
     * @param userCodeFile
     * @return
     */
    public ExecuteMessage compileFile(File userCodeFile){
        //拼接编译命令
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodeFile.getAbsolutePath());
        //获取 Process 对象 进行命令行操作
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
            if(executeMessage.getExitValue() != 0){
                throw  new RuntimeException("编译错误");
            }
            return executeMessage;
        } catch (IOException e) {
            //return getErrorResponse(e)
            throw  new RuntimeException(e);

        }
    }

    /**
     * 3.运行代码
     * @param inputList
     * @param userCodeFile
     * @return
     */
    public List<ExecuteMessage> runFile(List<String> inputList, File userCodeFile ){
        //获取运行信息列表，便于后续整理输出
        ArrayList<ExecuteMessage> executeMessageList = new ArrayList<>();
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();

        for (String inputArgs : inputList) {
            String runCmd = String.format("java -Xmx256m -Dfile.encoding=UTF-8 -cp %s Main %s", userCodeParentPath, inputArgs);
            try {
                Process runProcess = Runtime.getRuntime().exec(runCmd);
                //创建守护线程，进行超时控制
                new Thread(() -> {
                    try {
                        Thread.sleep(TIME_OUT);
                        if (runProcess.isAlive()) {
                            runProcess.destroy();
                            System.out.println("超时了");
                        }

                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }).start();

                ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(runProcess, "运行");
                //交互式执行进程并获取信息（new Scanner(System.in);）
                //ExecuteMessage executeMessage = ProcessUtils.runInteractProcessAndGetMessage(runProcess, inputArgs,"运行");
                executeMessageList.add(executeMessage);
            } catch (IOException e) {
               // return getErrorResponse(e);
                throw new RuntimeException("程序执行异常",e);
            }
        }
        return executeMessageList;
    }

    /**
     * 4.整理输出
     * @param executeMessageList
     * @return executeCodeResponse
     */
    public ExecuteCodeResponse getOutputResponse(List<ExecuteMessage> executeMessageList){
        //创建返回对象
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();

        ArrayList<String> outputList = new ArrayList<>();
        //使用最大值，判断是否执行超时
        long maxTime = 0;
        for (ExecuteMessage executeMessage : executeMessageList) {
            String errorMessage = executeMessage.getErrorMessage();
            if (StrUtil.isNotEmpty(errorMessage)) {
                //执行中存在错误
                executeCodeResponse.setMessage(errorMessage);
                //设置执行状态
                executeCodeResponse.setStatus(3);
            }

            //正常执行
            if(StrUtil.isNotEmpty(executeMessage.getMessage())){
                outputList.add(executeMessage.getMessage());
            }
            Long time = executeMessage.getTime();
            if (time != null) {
                maxTime = Math.max(maxTime, time);
            }
        }
        //设置执行输出信息
        executeCodeResponse.setOutputList(outputList);
        //正常执行完成
        if (outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(1);
        }
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);

        //设置执行过程信息
        executeCodeResponse.setJudgeInfo(judgeInfo);

        return executeCodeResponse;
    }

    /**
     * 5.清理/删除文件
     * @param userCodeFile
     * @return
     */
    public boolean deleteFile(File userCodeFile){
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();

        if (userCodeParentPath != null) {
            boolean del = FileUtil.del(userCodeParentPath);
            System.out.println("删除" + (del ? "成功" : "失败"));
            return del;
        }
        return true;

    }

    /**
     * 6.错误处理（定义一个异常处理方法）
     * @param e
     * @return
     */
    private ExecuteCodeResponse getErrorResponse(Throwable e) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(new ArrayList<>());
        executeCodeResponse.setMessage(e.getMessage());
        // 表示代码沙箱错误
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }

}
