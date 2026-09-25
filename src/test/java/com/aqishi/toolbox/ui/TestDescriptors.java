package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.catalog.ToolDescriptor;

import java.util.Arrays;

/** 导航与内容区测试用的假工具描述符，参数顺序沿用旧的「分类在前」写法。 */
final class TestDescriptors {

    private TestDescriptors() {
    }

    static ToolDescriptor of(String categoryId, String id, String... keywords) {
        return new ToolDescriptor(id, categoryId, "tool." + id, Arrays.asList(keywords));
    }
}
