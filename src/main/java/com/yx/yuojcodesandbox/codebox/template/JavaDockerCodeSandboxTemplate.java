package com.yx.yuojcodesandbox.codebox.template;


import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.yx.yuojcodesandbox.model.ExecuteCodeRequest;
import com.yx.yuojcodesandbox.model.ExecuteCodeResponse;
import com.yx.yuojcodesandbox.model.ExecuteMessage;
import com.yx.yuojcodesandbox.model.JudgeInfo;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Java Docker 语言的代码沙箱
 */
@Component
public class JavaDockerCodeSandboxTemplate extends CodeSandboxTemplate {

    //超时时间
    private static final long TIME_OUT = 5000L;

    //dockerJava客户端
    DockerClient dockerClient;

    //容器id
    String containerId;


    /**
     * 重写第三步 docker容器运行方法
     * @param inputList
     * @param userCodeFile
     * @return
     */
    @Override
    public List<ExecuteMessage> runFile(List<String> inputList, File userCodeFile) {
        //获取用户代码的父目录/文件夹路径
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
        //拉取镜像，创建容器、上传编译代码
        //创建docker客户端
         dockerClient = DockerClientBuilder.getInstance().build();

        //拉取镜像
        String image = "openjdk:8-alpine";
     /*   if (IS_PULL_IMAGE) {
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
        }*/

        //创建容器
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        //主机限制
        HostConfig hostConfig = new HostConfig();
        hostConfig.withMemory(100 * 1000 * 1000L);//设置内存限制
        hostConfig.withCpuCount(1L);//设置cpu限制
        hostConfig.setBinds(new Bind(userCodeParentPath, new Volume("/app")));//设置文件映射
        //hostConfig.setBinds(new Bind(SECURITY_MANAGER_PATH, new Volume("/app/securityManager")));//Java 安全管理器
        //hostConfig.withSecurityOpts(Arrays.asList("seccomp=" + ResourceUtil.readUtf8Str("profile.json")));//seccomp安全管理措施

        CreateContainerResponse createContainerResponse = containerCmd
                .withHostConfig(hostConfig)//主机限制：可限制内存、cpu等资源并能设置文件映射
                .withNetworkDisabled(true)//限制网络（禁用）
                .withReadonlyRootfs(true)//限制向 root 目录下写文件
                .withTty(true)//创建可 交互式 的容器以防止 一个提交中的每一个测试用例都创建一个容器
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withAttachStdin(true)
                .exec();
        containerId = createContainerResponse.getId();
        System.out.println(createContainerResponse);

//      4.启动容器，执行代码
        //启动容器
        dockerClient.startContainerCmd(containerId).exec();

        //执行代码
        List<ExecuteMessage> executeMessageList = new ArrayList<>(); //接收执行之后的结果信息对象
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
            String[] cmdArray = ArrayUtil.append(new String[]{"java", "-cp", "/app", "Main"}, inputArgsArray);
            //String[] cmdArray = ArrayUtil.append(new String[]{"java", "-cp", "/app", "Main",":/app/securityManager","-Djava.security.manager=CustomSecurityManager.class"}, inputArgsArray);//启用 Java安全管理措施
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmdArray)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .exec();
            System.out.println("创建执行命令" + execCreateCmdResponse);
            //获取执行命令的id
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
                        //message[0] = new String(frame.getPayload()).split("：")[1].split("\n")[0];
                        message[0] = new String(frame.getPayload()).split("\n")[0];
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
                    //System.out.println("内存占用：" + statistics.getMemoryStats().getUsage());
                    maxMemory[0] = Math.max(statistics.getMemoryStats().getUsage(), maxMemory[0]);
                }
                @Override
                public void close() throws IOException {}
                @Override
                public void onStart(Closeable closeable) {}
                @Override
                public void onError(Throwable throwable) {}
                @Override
                public void onComplete() {}
            });
            statsCmd.close();
            try {
                //定义计时器，统计耗时
                StopWatch stopWatch = new StopWatch();
                stopWatch.start();

                //启动命令，开始执行
                dockerClient.execStartCmd(execID)
                        .exec(execStartResultCallback)
                        .awaitCompletion(TIME_OUT, TimeUnit.MILLISECONDS);//设置超时时间

                stopWatch.stop();
                statsCmd.close();
                time = stopWatch.getLastTaskTimeMillis();
                //设置执行结果信息对象
                executeMessage.setErrorMessage(errorMessage[0]);
                executeMessage.setMessage(message[0]);
                executeMessage.setTime(time);
                executeMessage.setMemory(maxMemory[0]);
                executeMessageList.add(executeMessage);
            } catch (InterruptedException e) {
                System.out.println("程序运行异常");
                throw new RuntimeException(e);
            }
        }
        return executeMessageList;
    }

    /**
     * 重写第四步,加入内存返回
     * @param executeMessageList
     * @return
     */
    @Override
    public ExecuteCodeResponse getOutputResponse(List<ExecuteMessage> executeMessageList){
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

        return executeCodeResponse;
    }

    /**
     * 重写第五步，加上删除容器的操作
     * @param userCodeFile
     * @return
     */
    @Override
    public boolean deleteFile(File userCodeFile){
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();

        if (userCodeParentPath != null) {
            boolean del = FileUtil.del(userCodeParentPath);
            System.out.println("删除" + (del ? "成功" : "失败"));
        }
        //删除容器
        dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        System.out.println("容器"+containerId+"删除成功");
        return true;

    }


    /**
     * 继承原本沙箱的实现功能
     * @param request 判题参数
     * @return
     */
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest request) {
        return super.executeCode(request);
    }
}

