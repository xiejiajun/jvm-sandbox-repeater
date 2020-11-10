package com.alibaba.jvm.sandbox.repeater.plugin.core.impl;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.alibaba.jvm.sandbox.api.listener.EventListener;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatchBuilder;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatchBuilder.IBuildingForBehavior;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatchBuilder.IBuildingForClass;
import com.alibaba.jvm.sandbox.api.resource.ModuleEventWatcher;
import com.alibaba.jvm.sandbox.repeater.plugin.api.InvocationListener;
import com.alibaba.jvm.sandbox.repeater.plugin.api.InvocationProcessor;
import com.alibaba.jvm.sandbox.repeater.plugin.core.impl.api.DefaultEventListener;
import com.alibaba.jvm.sandbox.repeater.plugin.core.model.EnhanceModel;
import com.alibaba.jvm.sandbox.repeater.plugin.domain.RepeaterConfig;
import com.alibaba.jvm.sandbox.repeater.plugin.exception.PluginLifeCycleException;
import com.alibaba.jvm.sandbox.repeater.plugin.spi.InvokePlugin;

import com.google.common.collect.Lists;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AbstractInvokePluginAdapter}是{@link InvokePlugin}的抽象适配，提供了标准的模块生命周期处理流程；
 * <p>
 * 同时注入了{@link com.alibaba.jvm.sandbox.repeater.plugin.core.impl.api.DefaultInvocationListener}
 * <p>
 *
 * @author zhaoyb1990
 */
public abstract class AbstractInvokePluginAdapter implements InvokePlugin {

    protected final static Logger log = LoggerFactory.getLogger(AbstractInvokePluginAdapter.class);

    protected volatile RepeaterConfig configTemporary;

    /**
     * TODO 这个类是jvm-sandbox中通过字节码增强技术对用户指定的方法进行增强的入口
     */
    private ModuleEventWatcher watcher;

    private List<Integer> watchIds = Lists.newCopyOnWriteArrayList();

    private InvocationListener listener;

    private AtomicBoolean watched = new AtomicBoolean(false);

    @Override
    public void onLoaded() throws PluginLifeCycleException {
        // default no-op
    }

    @Override
    public void onActive() throws PluginLifeCycleException {
        // default no-op
    }

    @Override
    public void watch(ModuleEventWatcher watcher,
                      InvocationListener listener) throws PluginLifeCycleException {
        this.watcher = watcher;
        this.listener = listener;
        watchIfNecessary();
    }

    @Override
    public void unWatch(ModuleEventWatcher watcher, InvocationListener listener) {
        if (CollectionUtils.isNotEmpty(watchIds)) {
            for (Integer watchId : watchIds) {
                // TODO 这个方法调用会卸载已有的增强逻辑
                watcher.delete(watchId);
            }
            watchIds.clear();
        }
        watched.compareAndSet(true, false);
    }

    @Override
    public void reWatch(ModuleEventWatcher watcher,
                        InvocationListener listener) throws PluginLifeCycleException {
        this.unWatch(watcher, listener);
        watch(watcher, listener);
    }

    @Override
    public void onFrozen() throws PluginLifeCycleException {
        // default no-op
    }

    @Override
    public void onUnloaded() throws PluginLifeCycleException {
        // default no-op
    }

    @Override
    public void onConfigChange(RepeaterConfig config) throws PluginLifeCycleException {
        // default no-op;plugin can override this method to aware config change
        this.configTemporary = config;
    }

    @Override
    public boolean enable(RepeaterConfig config) {
        return config != null && config.getPluginIdentities().contains(identity());
    }

    protected void reWatch0() throws PluginLifeCycleException {
        reWatch(watcher, listener);
    }

    /**
     * 执行观察事件
     *
     * @throws PluginLifeCycleException 插件异常
     */
    private synchronized void watchIfNecessary() throws PluginLifeCycleException {
        if (watched.compareAndSet(false, true)) {
            // TODO getEnhanceModels由用户自定义插件实现
            List<EnhanceModel> enhanceModels = getEnhanceModels();
            if (CollectionUtils.isEmpty(enhanceModels)) {
                throw new PluginLifeCycleException("enhance models is empty, plugin type is " + identity());
            }
            for (EnhanceModel em : enhanceModels) {
                IBuildingForBehavior behavior = null;
                IBuildingForClass builder4Class = new EventWatchBuilder(watcher).onClass(em.getClassPattern());
                if (em.isIncludeSubClasses()) {
                    builder4Class = builder4Class.includeSubClasses();
                }
                for (EnhanceModel.MethodPattern mp : em.getMethodPatterns()) {
                    behavior = builder4Class.onBehavior(mp.getMethodName());
                    if (ArrayUtils.isNotEmpty(mp.getParameterType())) {
                        behavior.withParameterTypes(mp.getParameterType());
                    }
                    if (ArrayUtils.isNotEmpty(mp.getAnnotationTypes())) {
                        behavior.hasAnnotationTypes(mp.getAnnotationTypes());
                    }
                }
                // TODO 上面这一段逻辑都是在添加需要增强的类方法的patten表达式
                if (behavior != null) {
                    // TODO 这里的onWatch方法最终会根据上面的一大堆patten等配置对匹配上的业务对象对应的class进行字节码增强，增强完后
                    //  业务对象调用的就是植入了我们定义的流量录制回放逻辑的流程了(本质上是替换了JVM方法区对应类的字节码，而对象执行方法时
                    //  就是读取这些字节码交由JVM解释执行的)
                    //  getEventListener由用户自定义插件实现，方便用户定义自己的EventListener来处理录制会回放相关逻辑（录制结果
                    //  存在哪里，怎么回放，满足什么条件回放由用户自己控制)
                    int watchId = behavior.onWatch(getEventListener(listener), em.getWatchTypes()).getWatchId();
                    watchIds.add(watchId);
                    log.info("add watcher success,type={},watcherId={}", getType().name(), watchId);
                }
            }
        }
    }

    /**
     * 获取需要增强的类模型
     *
     * @return enhanceModels
     */
    abstract protected List<EnhanceModel> getEnhanceModels();

    /**
     * 返回调用过程处理器，用于处理入参、返回值等
     *
     * @return invocationProcessor构造结果
     */
    abstract protected InvocationProcessor getInvocationProcessor();

    /**
     * 返回事件监听器 - 子类若参数的组装方式不适配，可以重写改方法
     *
     * @param listener 调用监听
     * @return 事件监听器
     */
    protected EventListener getEventListener(InvocationListener listener) {
        return new DefaultEventListener(getType(), isEntrance(), listener, getInvocationProcessor());
    }
}
