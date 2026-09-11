package com.aqishi.toolbox.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Component;

/**
 * 异常处理门面。
 *
 * <p>取代散落各处的 {@code e.printStackTrace()} 与空 {@code catch {}}：前者把堆栈丢给
 * 一个桌面应用通常根本没有的控制台，后者连堆栈都没有，两者都让线上问题无从取证。</p>
 *
 * <p>按「这个异常要不要让用户知道」分成三档：</p>
 * <ul>
 *   <li>{@link #report} —— 用户发起的操作失败了：记 ERROR 并弹错误框</li>
 *   <li>{@link #log} —— 后台流程失败，界面另有反馈或无需打扰用户：只记 ERROR</li>
 *   <li>{@link #ignored} —— 确实可以忽略（清理路径、探测性调用）：记 DEBUG 留痕</li>
 * </ul>
 *
 * <p>日志名取的是调用方的类，所以日志里看到的仍是出事的那个类，而不是本类。</p>
 */
public final class Errors {

    private static final StackWalker WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private Errors() {
    }

    /**
     * 用户操作失败：记录完整堆栈并弹出错误提示。
     *
     * @param parent      对话框父组件，可为 null
     * @param userMessage 给用户看的说明，不应包含堆栈
     * @param error       原始异常
     */
    public static void report(Component parent, String userMessage, Throwable error) {
        callerLogger().error("{} - {}", userMessage, describe(error), error);
        UIUtils.error(parent, userMessage + detailSuffix(error));
    }

    /** 同 {@link #report}，但使用自定义对话框标题 */
    public static void report(Component parent, String userMessage, String title, Throwable error) {
        callerLogger().error("{} - {}", userMessage, describe(error), error);
        UIUtils.error(parent, userMessage + detailSuffix(error), title);
    }

    /** 后台流程失败：只记录，不打扰用户 */
    public static void log(String context, Throwable error) {
        callerLogger().error("{} - {}", context, describe(error), error);
    }

    /**
     * 可忽略的异常：记 DEBUG 留痕。
     *
     * @param reason 为什么可以忽略——写清楚，这是空 catch 与「有意忽略」的唯一区别
     */
    public static void ignored(String reason, Throwable error) {
        Logger logger = callerLogger();
        if (logger.isDebugEnabled()) {
            logger.debug("已忽略：{} - {}", reason, describe(error), error);
        }
    }

    /** 异常的单行摘要，用于日志正文与提示框补充说明 */
    public static String describe(Throwable error) {
        if (error == null) {
            return "(无异常信息)";
        }
        String message = error.getMessage();
        String type = error.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    private static String detailSuffix(Throwable error) {
        return error == null ? "" : System.lineSeparator() + describe(error);
    }

    /**
     * 取调用方所在类的 Logger。
     *
     * <p>栈上第一帧是本类自己的某个方法，跳过后即为调用方。</p>
     */
    private static Logger callerLogger() {
        Class<?> caller = WALKER.walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> type != Errors.class)
                .findFirst()
                .orElse(Errors.class));
        return LoggerFactory.getLogger(caller);
    }
}
