package com.yx.yuojcodesandbox.codebox.implOld;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.FoundWord;
import cn.hutool.dfa.WordTree;
import com.yx.yuojcodesandbox.codebox.CodeSandbox;
import com.yx.yuojcodesandbox.model.ExecuteCodeRequest;
import com.yx.yuojcodesandbox.model.ExecuteCodeResponse;
import com.yx.yuojcodesandbox.model.ExecuteMessage;
import com.yx.yuojcodesandbox.model.JudgeInfo;
import com.yx.yuojcodesandbox.utils.ProcessUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;


/**
 * Java 语言的代码沙箱
 */
public class JavaNativeCodeSandbox implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    // 超时时间
    private static final long TIME_OUT = 5000L;

    //操作黑名单
    private static final List<String> BLACK_LIST = Arrays.asList("exec","File");

    // 操作黑名单字典树
    private static final WordTree BLACK_WORDTREE = new WordTree();
    static {
        //初始化字典树
        BLACK_WORDTREE.addWords(BLACK_LIST);
    }
    //使用布隆过滤器实现操作黑名单
/*    private static final BitMapBloomFilter BLACK_BLOOM_FILTER = new BitMapBloomFilter(10);
    static {
        //初始化布隆过滤器
        for (String s : BLACK_LIST) {
            BLACK_BLOOM_FILTER.add(s);
        }
    }*/

    //自定义安全管理器相关配置
    public static final String SECURITY_MANAGER_PATH = "G:\\Java\\PersonalProject\\OJ\\yuoj-code-sandbox\\src\\main\\resources\\securityManager";
    public static final String SECURITY_MANAGER_CLASS_NAME = "MySecurityManager";






     public static void main(String[] args) {
        JavaNativeCodeSandbox javaNativeCodeSandbox = new JavaNativeCodeSandbox();
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        //String code = ResourceUtil.readStr("testCode"+File.separator+"Main.java", StandardCharsets.UTF_8);
        String code = ResourceUtil.readStr("testCode"+ File.separator+"Main.java", StandardCharsets.UTF_8);
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("java");
        executeCodeRequest.setInputList(Arrays.asList("1 2","3 4"));

        ExecuteCodeResponse executeCodeResponse = javaNativeCodeSandbox.executeCode(executeCodeRequest);
        System.out.println(executeCodeResponse);
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest request) {
//      1.保存代码文件
        //先判断要存储程序的文件夹是否存在，不存在创建（实际无需每次都判断创建）
        String code = request.getCode();

        //  使用字典树校验代码中是否包含黑名单中的禁用词
        FoundWord foundWord = BLACK_WORDTREE.matchWord(code);
        if (foundWord != null) {
            System.out.println("包含禁止词：" + foundWord.getFoundWord());
            return null;
        }
        // 使用布隆过滤器校验代码中是否包含黑名单中的禁用词
     /*   String[] strings = code.split("\\W+");
        for (String string : strings) {
            if (BLACK_BLOOM_FILTER.contains(string)) {
                System.out.println("包含禁止词" + string);
                return null;
            }
        }*/


        // 获取当前项目的路径
        String UserDir = System.getProperty("user.dir");
        // 拼接文件夹路径
        String globalCodePathName = UserDir + File.separator + GLOBAL_CODE_DIR_NAME;
        //判断全局代码目录是否存在，不存在则创建
        if(!FileUtil.exist(globalCodePathName)){
            FileUtil.mkdir(globalCodePathName);
        }
        //把用户代码进行隔离：每一个代码创建一个临时目录
        String userCodeParentPath = globalCodePathName + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParentPath + File.separator + GLOBAL_JAVA_CLASS_NAME;
        //将用户的代码存到文件中 （HuTool工具写入）
        File userCodeFile = FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);


//      2.编译代码
        //拼接编译命令
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodeFile.getAbsolutePath());
        /*try {
            //获取 Process 对象 进行命令行操作
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            //获取运行之后等待值（0-成功）
            int exitValue = compileProcess.waitFor();
            //根据等待值，进行判断，输出编译后的命令行输出
            if(exitValue == 0){
                System.out.println("编译成功");
                //分批获取进程的正常输出
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(compileProcess.getInputStream()));
                StringBuilder compileOutputStringBuilder = new StringBuilder();
                //逐行读取
                String compileOutputLine;
                while ((compileOutputLine = bufferedReader.readLine())!= null){
                    compileOutputStringBuilder.append(compileOutputLine);
                }
                System.out.println("编译成功输出：" + compileOutputStringBuilder);

            }else {
                System.out.println("编译失败，错误码"+ exitValue);
                //分批获取进程的正常输出
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(compileProcess.getInputStream()));
                StringBuilder compileOutputStringBuilder = new StringBuilder();
                //逐行读取
                String compileOutputLine;
                while ((compileOutputLine = bufferedReader.readLine())!= null){
                    compileOutputStringBuilder.append(compileOutputLine);
                }

                //分批获取进程的错误输出
                BufferedReader errorBufferedReader = new BufferedReader(new InputStreamReader(compileProcess.getErrorStream()));
                StringBuilder errorCompileOutputStringBuilder = new StringBuilder();
                //逐行读取
                String errorCompileOutputLine;
                while ((errorCompileOutputLine = bufferedReader.readLine())!= null){
                    errorCompileOutputStringBuilder.append(errorCompileOutputLine);
                }

                System.out.println("编译输出：" + compileOutputStringBuilder);
                System.out.println("编译错误输出：" + errorCompileOutputStringBuilder);
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }*/
        //获取 Process 对象 进行命令行操作
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
            System.out.println(executeMessage);

        } catch (IOException e) {
            return  getErrorResponse(e);
        }


//      3.运行代码
        //获取运行信息列表，便于后续整理输出
        ArrayList<ExecuteMessage> executeMessageList = new ArrayList<>();

        List<String> inputList = request.getInputList();
        for (String inputArgs : inputList) {
            String runCmd = String.format("java -Xmx256m -Dfile.encoding=UTF-8 -cp %s;%s -Djava.security.manager=%s  Main %s", userCodeParentPath, SECURITY_MANAGER_PATH,SECURITY_MANAGER_CLASS_NAME,inputArgs);
            //String runCmd = String.format("java -Dfile.encoding=UTF-8 -cp %s InteractMain ", userCodeParentPath);
            try {
                Process runProcess = Runtime.getRuntime().exec(runCmd);
                //创建守护线程，进行超时控制
                new Thread(()->{
                    try {
                        Thread.sleep(TIME_OUT);
                        if(runProcess.isAlive()){
                            runProcess.destroy();
                            System.out.println("超时了");
                        }

                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }).start();

                ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(runProcess, "运行");
                //ExecuteMessage executeMessage = ProcessUtils.runInteractProcessAndGetMessage(runProcess, inputArgs,"运行");
                executeMessageList.add(executeMessage);
            } catch (IOException e) {
                return  getErrorResponse(e);
            }
        }

//      4.整理输出
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();

        ArrayList<String> outputList = new ArrayList<>();
        //使用最大值，判断是否执行超时
        long maxTime = 0;
        for (ExecuteMessage executeMessage : executeMessageList) {
            String errorMessage = executeMessage.getErrorMessage();
            if(StrUtil.isNotEmpty(errorMessage)){
                executeCodeResponse.setMessage(errorMessage);
                //执行中存在错误
                executeCodeResponse.setStatus(3);
            }

            outputList.add(executeMessage.getMessage());
            Long time = executeMessage.getTime();
            if(time != null){
                maxTime = Math.max(maxTime, time);
            }
        }
        executeCodeResponse.setOutputList(outputList);
        //正常执行完成
        if(outputList.size() == executeMessageList.size()){
            executeCodeResponse.setStatus(1);
        }
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);

        executeCodeResponse.setJudgeInfo(judgeInfo);


//      5.文件清理
        if(userCodeParentPath != null){
            boolean del = FileUtil.del(userCodeParentPath);
            System.out.println("删除" + (del ? "成功" : "失败"));
        }

        return executeCodeResponse;

    }
//      6.错误处理（定义一个异常处理方法）
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

