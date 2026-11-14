package com.paicli.policy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * W2 良性命令验收：B1–B16 必须全部放行。
 *
 * <p>只调用纯判定函数 {@link CommandGuard#check(String)}，不执行任何命令。</p>
 */
class W2CommandBenignTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "Remove-Item target -Recurse -Force",
            "Remove-Item .\\target -Recurse -Force",
            "Remove-Item .\\build -Recurse -Force",
            "mvn clean",
            "mvn -o -q test",
            "Get-ChildItem",
            "Get-ChildItem . -Recurse",
            "Get-Content pom.xml",
            "git status",
            "git diff --stat",
            "git log --oneline -n 5",
            "Select-String -Path target\\surefire-reports\\*.txt -Pattern \"Tests run\"",
            "cmd /c dir",
            "powershell -NoProfile -Command \"Get-ChildItem\"",
            "del /q .\\build\\*",
            "rm -rf ./target"
    })
    void benignCommandMustBeAllowed(String command) {
        assertNull(CommandGuard.check(command), "良性命令被误拦: " + command);
    }
}
