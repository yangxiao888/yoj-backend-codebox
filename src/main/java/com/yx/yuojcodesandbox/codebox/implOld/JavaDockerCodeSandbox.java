package com.yx.yuojcodesandbox.codebox.implOld;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.FoundWord;
import cn.hutool.dfa.WordTree;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.yx.yuojcodesandbox.codebox.CodeSandbox;
import com.yx.yuojcodesandbox.model.ExecuteCodeRequest;
import com.yx.yuojcodesandbox.model.ExecuteCodeResponse;
import com.yx.yuojcodesandbox.model.ExecuteMessage;
import com.yx.yuojcodesandbox.model.JudgeInfo;
import com.yx.yuojcodesandbox.utils.ProcessUtils;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


/**
 * Java 语言的代码沙箱
 */
public class JavaDockerCodeSandbox implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    public static Boolean IS_PULL_IMAGE = false;

    //操作黑名单
    private static final List<String> BLACK_LIST = Arrays.asList("exec", "File");
    // 操作黑名单字典树
    private static final WordTree BLACK_WORDTREE = new WordTree();

    static {
        //初始化字典树
        BLACK_WORDTREE.addWords(BLACK_LIST);
    }

    // 超时时间
    private static final long TIME_OUT = 5000L;

    //自定义安全管理器相关配置
    public static final String SECURITY_MANAGER_PATH = "/home/yangxiao/yuoj-code-sandbox/src/main/resources/securityManager";
    public static final String SECURITY_MANAGER_CLASS_NAME = "CustomSecurityManager";


    public static void main(String[] args) {
        JavaDockerCodeSandbox javaNativeCodeSandbox = new JavaDockerCodeSandbox();
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        String code = ResourceUtil.readStr("testCode" + File.separator + "Main.java", StandardCharsets.UTF_8);
        //String code = ResourceUtil.readStr("testCode"+ File.separator+"writeMain.java", StandardCharsets.UTF_8);
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("java");
        executeCodeRequest.setInputList(Arrays.asList("1 2", "3 4"));

        ExecuteCodeResponse executeCodeResponse = javaNativeCodeSandbox.executeCode(executeCodeRequest);
        System.out.println(executeCodeResponse);
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest request) {
//      限制代码
        //  使用字典树校验代码中是否包含黑名单中的禁用词
        String code = request.getCode();
        FoundWord foundWord = BLACK_WORDTREE.matchWord(code);
        if (foundWord != null) {
            System.out.println("包含禁止词：" + foundWord.getFoundWord());
            return null;
        }
//      1.保存代码文件
        //先判断要存储程序的文件夹是否存在，不存在创建（实际无需每次都判断创建）
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


//      2.编译代码
        //拼接编译命令
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodeFile.getAbsolutePath());
        //获取 Process 对象 进行命令行操作

        Process compileProcess = null;
        try {
            compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
            System.out.println(executeMessage);
        } catch (IOException e) {
            System.out.println("编译失败");
            return getErrorResponse(e);
        }


//      3.拉取镜像，创建容器、上传编译代码
        //创建docker客户端
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();

        //拉取镜像
        String image = "openjdk:8-alpine";
        if (IS_PULL_IMAGE) {
            PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
            PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
                @Override
                public void onNext(PullResponseItem item) {
                    System.out.println("下载镜像：" + item.getStatus());
                    super.onNext(item);
                }
            };
            try {
                pullImageCmd
                        .exec(pullImageResultCallback)
                        .awaitCompletion();
            } catch (InterruptedException e) {
                System.out.println("镜像下载失败");
                throw new RuntimeException(e);
            }
            System.out.println("下载完成");
            IS_PULL_IMAGE = false;
        }

        //创建容器
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        HostConfig hostConfig = new HostConfig();
        hostConfig.withMemory(100 * 1000 * 1000L);
        hostConfig.withCpuCount(1L);
        hostConfig.setBinds(new Bind(userCodeParentPath, new Volume("/app")));
        hostConfig.setBinds(new Bind(SECURITY_MANAGER_PATH, new Volume("/app/securityManager")));
        hostConfig.withSecurityOpts(Arrays.asList("seccomp=" + ResourceUtil.readUtf8Str("profile.json")));

        CreateContainerResponse createContainerResponse = containerCmd
                .withHostConfig(hostConfig)//可限制内存、cpu等资源并能设置文件映射
                .withNetworkDisabled(true)//限制网络
                .withReadonlyRootfs(true)//限制向 root 目录下写文件
                .withTty(true)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withAttachStdin(true)
                .exec();
        String containerId = createContainerResponse.getId();
        System.out.println(createContainerResponse);

//      4.启动容器，执行代码
        //启动容器
        dockerClient.startContainerCmd(containerId).exec();

        //执行代码
        List<ExecuteMessage> executeMessageList = new ArrayList<>(); //接收执行之后的结果信息对象
        List<String> inputList = request.getInputList();
        for (String inputArgs : inputList) {
            //执行过程中的信息对象
            ExecuteMessage executeMessage = new ExecuteMessage();
            final String[] message = {null};
            final String[] errorMessage = {null};
            long time = 0L;
            final long[] maxMemory = {0L};
            final boolean[] timeout = {true};//是否超时

            //创建命令
            String[] inputArgsArray = inputArgs.split(" ");
            //String[] cmdArray = ArrayUtil.append(new String[]{"java", "-cp", "/app", "Main"}, inputArgsArray);
            String[] cmdArray = ArrayUtil.append(new String[]{"java", "-cp", "/app", "Main",":/app/securityManager","-Djava.security.manager=CustomSecurityManager.class"}, inputArgsArray);
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmdArray)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .exec();
            System.out.println("创建执行命令" + execCreateCmdResponse);
            String execID = execCreateCmdResponse.getId();


            //执行命令
            ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() {
                @Override
                public void onComplete() {
                    timeout[0] = false;
                    super.onComplete();
                }

                @Override
                public void onNext(Frame frame) {
                    StreamType streamType = frame.getStreamType();
                    if (StreamType.STDERR.equals(streamType)) {
                        errorMessage[0] = new String(frame.getPayload());
                        System.out.println("错误输出结果是：" + errorMessage[0]);
                    } else {
                        message[0] = new String(frame.getPayload()).split("：")[1];
                        System.out.println("正确输出结果是：" + message[0]);
                    }
                    super.onNext(frame);
                }
            };
            // 获取占用的内存
            StatsCmd statsCmd = dockerClient.statsCmd(containerId);
            statsCmd.exec(new ResultCallback<Statistics>() {

                @Override
                public void onNext(Statistics statistics) {
                    System.out.println("内存占用：" + statistics.getMemoryStats().getUsage());
                    maxMemory[0] = Math.max(statistics.getMemoryStats().getUsage(), maxMemory[0]);
                }

                @Override
                public void close() throws IOException {

                }

                @Override
                public void onStart(Closeable closeable) {

                }

                @Override
                public void onError(Throwable throwable) {

                }

                @Override
                public void onComplete() {

                }
            });
            statsCmd.close();
            try {
                //定义计时器，统计耗时
                StopWatch stopWatch = new StopWatch();
                stopWatch.start();

                dockerClient.execStartCmd(execID)
                        .exec(execStartResultCallback)
                        .awaitCompletion(TIME_OUT, TimeUnit.MILLISECONDS);

                statsCmd.close();
                stopWatch.stop();
                time = stopWatch.getLastTaskTimeMillis();
                executeMessage.setErrorMessage(errorMessage[0]);
                executeMessage.setMessage(message[0]);
                executeMessage.setTime(time);
                executeMessage.setMemory(maxMemory[0]);
                executeMessageList.add(executeMessage);
            } catch (InterruptedException e) {
                System.out.println("程序运行异常");
                return getErrorResponse(e);
            }
        }


//      5.整理输出
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();

        ArrayList<String> outputList = new ArrayList<>();
        //使用最大值，判断是否执行超时
        long maxTime = 0;
        long maxmemory = 0;
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
            Long memory = executeMessage.getMemory();
            if(memory != null){
                maxmemory = Math.max(maxmemory, memory);
            }
        }
        executeCodeResponse.setOutputList(outputList);
        //正常执行完成
        if(outputList.size() == executeMessageList.size()){
            executeCodeResponse.setStatus(1);
        }
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        judgeInfo.setMemory(maxmemory);

        executeCodeResponse.setJudgeInfo(judgeInfo);


//      6.文件、容器清理
        if(userCodeParentPath != null){
            boolean del = FileUtil.del(userCodeParentPath);
            System.out.println("删除" + (del ? "成功" : "失败"));
        }
        //删除容器
        dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        System.out.println("容器"+containerId+"删除成功");

        return executeCodeResponse;
    }

//      7.错误处理（定义一个异常处理方法）
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

