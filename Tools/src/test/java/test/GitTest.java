package test;

import com.pqixing.Tools;
import com.pqixing.git.GitUtils;

import com.pqixing.interfaces.ICredential;
import com.pqixing.interfaces.ILog;
import com.pqixing.shell.Shell;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

import java.io.File;

public class GitTest {
    @Test
    public void testClone() throws GitAPIException {
        init();
        long start = System.currentTimeMillis();
        GitUtils.clone("https://github.com/pqixing/modularization.git", "/home/pqixing/Desktop/test2");
        System.out.println("end count " + (System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        Shell.runSync("git clone --progress https://github.com/pqixing/modularization.git", new File("/home/pqixing/Desktop/test"));
        System.out.println("end count " + (System.currentTimeMillis() - start));
    }

    private void init() {
        Tools.init(new ILog() {
            @Override
            public void println(@Nullable String l) {
                System.out.println(l);
            }
        }, "", new ICredential() {
            @NotNull
            @Override
            public String getUserName() {
                return "pengqixing";
            }

            @NotNull
            @Override
            public String getPassWord() {
                return "pengqixing";
            }
        });
    }
}
