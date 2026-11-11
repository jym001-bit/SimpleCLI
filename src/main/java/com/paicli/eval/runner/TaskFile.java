package com.paicli.eval.runner;

import java.util.List;

/** tasks.json 的根节点。 */
public record TaskFile(List<TaskSpec> tasks) {
}
