
import java.security.Permission;

/**
 * OJ 场景自定义安全管理器：拦截危险操作，只开放最小必需权限
 */
public class CustomSecurityManager extends SecurityManager {

    // 禁止的危险命令（可根据需求扩展）
    private static final String[] FORBIDDEN_COMMANDS = {"rm", "sh", "bash", "su", "sudo", "wget", "curl"};

    /**
     * 所有权限检查的入口：先执行自定义检查，再走策略文件规则
     */
    @Override
    public void checkPermission(Permission perm) {
        // 1. 禁止修改/替换安全管理器（防止被篡改）
        if (perm instanceof RuntimePermission) {
            String permissionName = perm.getName();
            if ("setSecurityManager".equals(permissionName) 
                    || "createSecurityManager".equals(permissionName)) {
                throw new SecurityException("禁止修改安全管理器！");
            }
            // 禁止退出虚拟机（防止代码提前终止，影响结果判断）
            if ("exitVM".equals(permissionName)) {
                throw new SecurityException("禁止调用 System.exit()！");
            }
            // 禁止执行系统命令（Runtime.exec() 等）
            if ("exec".equals(permissionName)) {
                throw new SecurityException("禁止执行系统命令！");
            }
            // 禁止创建类加载器（防止加载恶意类）
            if ("createClassLoader".equals(permissionName)) {
                throw new SecurityException("禁止创建自定义类加载器！");
            }
        }

        // 2. 禁止网络操作（Socket 连接、监听等）
        if (perm instanceof java.net.SocketPermission) {
            throw new SecurityException("禁止网络操作！");
        }

        // 3. 禁止访问敏感系统目录（/etc、/root、/proc 等，除了 /app 和系统必需目录）
        if (perm instanceof java.io.FilePermission) {
            String filePath = perm.getName();
            String actions = perm.getActions();

            // 允许操作 /app 目录（OJ 代码存放目录）
            if (filePath.startsWith("/app/") || "/app".equals(filePath)) {
                // 允许读/写/删除 /app 下的文件（代码编译、输出结果）
                if (actions.contains("read") || actions.contains("write") || actions.contains("delete")) {
                    return; // 直接放行，不抛出异常
                }
            }

            // 禁止访问敏感目录
            if (filePath.startsWith("/etc/") 
                    || filePath.startsWith("/root/") 
                    || filePath.startsWith("/proc/") 
                    || filePath.startsWith("/sys/") 
                    || filePath.startsWith("/dev/")) {
                throw new SecurityException("禁止访问敏感目录：" + filePath);
            }

            // 禁止写/删除系统目录（如 /tmp 防止创建恶意文件）
            if ((actions.contains("write") || actions.contains("delete")) && !filePath.startsWith("/app/")) {
                throw new SecurityException("禁止修改系统文件：" + filePath);
            }
        }

        // 4. 其他权限：沿用策略文件配置（如线程、反射基础权限）
        super.checkPermission(perm);
    }

    /**
     * 额外检查：执行命令时的参数（双重防护，防止绕过 exec 限制）
     */
    @Override
    public void checkExec(String cmd) {
        for (String forbiddenCmd : FORBIDDEN_COMMANDS) {
            if (cmd.contains(forbiddenCmd)) {
                throw new SecurityException("禁止执行危险命令：" + cmd);
            }
        }
        super.checkExec(cmd);
    }

    /**
     * 额外检查：文件读取（双重防护）
     */
    @Override
    public void checkRead(String file) {
        if (file.startsWith("/etc/passwd") || file.startsWith("/etc/shadow")) {
            throw new SecurityException("禁止读取敏感文件：" + file);
        }
        super.checkRead(file);
    }

    /**
     * 额外检查：文件写入（双重防护）
     */
    @Override
    public void checkWrite(String file) {
        if (!file.startsWith("/app/")) {
            throw new SecurityException("禁止写入非 /app 目录：" + file);
        }
        super.checkWrite(file);
    }

    /**
     * 额外检查：文件删除（双重防护）
     */
    @Override
    public void checkDelete(String file) {
        if (!file.startsWith("/app/")) {
            throw new SecurityException("禁止删除非 /app 目录文件：" + file);
        }
        super.checkDelete(file);
    }
}