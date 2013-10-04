package com.nhn.pinpoint.profiler.context;

import com.nhn.pinpoint.common.AnnotationKey;
import com.nhn.pinpoint.common.ServiceType;
import com.nhn.pinpoint.common.util.ParsingResult;
import com.nhn.pinpoint.profiler.interceptor.MethodDescriptor;
import com.nhn.pinpoint.profiler.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;


/**
 * @author netspider
 */
public final class DefaultTrace implements Trace {

    private static final Logger logger = LoggerFactory.getLogger(DefaultTrace.class.getName());
    private static final boolean isDebug = logger.isDebugEnabled();
    private static final boolean isTrace = logger.isTraceEnabled();

    private short sequence;

    private boolean sampling = true;

    private final CallStack callStack;

    private Storage storage;

    private final TraceContext traceContext;

    // use for calculating depth of each Span.                                                                               
    private int latestStackIndex = -1;
    private StackFrame currentStackFrame;

    public DefaultTrace(TraceContext traceContext, String agentId, long agentStartTime, long transactionId) {
        if (traceContext == null) {
            throw new NullPointerException("traceContext must not be null");
        }
        this.traceContext = traceContext;
        final TraceId traceId = new DefaultTraceId(agentId, agentStartTime, transactionId);

        this.callStack = new CallStack(traceId);
        latestStackIndex = this.callStack.push();
        StackFrame stackFrame = createSpanStackFrame(ROOT_STACKID, callStack.getSpan());
        this.callStack.setStackFrame(stackFrame);
        this.currentStackFrame = stackFrame;
    }

    public DefaultTrace(TraceContext traceContext, TraceId continueTraceID) {
        if (traceContext == null) {
            throw new NullPointerException("traceContext must not be null");
        }
        if (continueTraceID == null) {
            throw new NullPointerException("continueTraceID must not be null");
        }
        this.traceContext = traceContext;
        this.callStack = new CallStack(continueTraceID);
        latestStackIndex = this.callStack.push();
        StackFrame stackFrame = createSpanStackFrame(ROOT_STACKID, callStack.getSpan());
        this.callStack.setStackFrame(stackFrame);
        this.currentStackFrame = stackFrame;
    }

    public CallStack getCallStack() {
        return callStack;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public short getSequence() {
        return sequence++;
    }

    public int getCallStackDepth() {
        return this.callStack.getIndex();
    }

    @Override
    public AsyncTrace createAsyncTrace() {
        // 경우에 따라 별도 timeout 처리가 있어야 될수도 있음.                                                               
        SpanEvent spanEvent = new SpanEvent(callStack.getSpan());
        spanEvent.setSequence(getSequence());
        DefaultAsyncTrace asyncTrace = new DefaultAsyncTrace(spanEvent);
        // asyncTrace.setDataSender(this.getDataSender());                                                                   
        asyncTrace.setStorage(this.storage);
        return asyncTrace;
    }

    private StackFrame createSpanEventStackFrame(int stackId) {
        SpanEvent spanEvent = new SpanEvent(callStack.getSpan());
        SpanEventStackFrame stackFrame = new SpanEventStackFrame(spanEvent);
        stackFrame.setStackFrameId(stackId);
        stackFrame.setSequence(getSequence());
        return stackFrame;
    }

    private StackFrame createSpanStackFrame(int stackId, Span span) {
        RootStackFrame stackFrame = new RootStackFrame(span);
        stackFrame.setStackFrameId(stackId);
        stackFrame.setSpan(span);
        stackFrame.setStackFrameId(ROOT_STACKID);
        return stackFrame;
    }

    @Override
    public void traceBlockBegin() {
        traceBlockBegin(DEFAULT_STACKID);
    }

    @Override
    public void markBeforeTime() {
        this.currentStackFrame.markBeforeTime();
    }

    @Override
    public long getBeforeTime() {
        return this.currentStackFrame.getBeforeTime();
    }

    @Override
    public void markAfterTime() {
        this.currentStackFrame.markAfterTime();
    }

    @Override
    public long getAfterTime() {
        return this.currentStackFrame.getAfterTime();
    }


    @Override
    public void traceBlockBegin(final int stackId) {
        final int currentStackIndex = callStack.push();
        final StackFrame stackFrame = createSpanEventStackFrame(stackId);

        if (latestStackIndex != currentStackIndex) {
            latestStackIndex = currentStackIndex;
            SpanEvent spanEvent = ((SpanEventStackFrame) stackFrame).getSpanEvent();
            spanEvent.setDepth(latestStackIndex);
        }

        callStack.setStackFrame(stackFrame);
        this.currentStackFrame = stackFrame;
    }

    @Override
    public void traceRootBlockEnd() {
        pop(ROOT_STACKID);
        callStack.popRoot();
        // 잘못된 stack 조작시 다음부터 그냥 nullPointerException이 발생할건데 괜찮은가?
        this.currentStackFrame = null;
    }

    @Override
    public void traceBlockEnd() {
        traceBlockEnd(DEFAULT_STACKID);
    }


    @Override
    public void traceBlockEnd(int stackId) {
        pop(stackId);
        StackFrame popStackFrame = callStack.pop();
        // pop 할때 frame위치를 원복해야 한다.
        this.currentStackFrame = popStackFrame;
    }

    private void pop(int stackId) {
        final StackFrame currentStackFrame = this.currentStackFrame;
        int stackFrameId = currentStackFrame.getStackFrameId();
        if (stackFrameId != stackId) {
            // 자체 stack dump를 하면 오류발견이 쉬울것으로 생각됨
            if (logger.isWarnEnabled()) {
                logger.warn("Corrupted CallStack found. StackId not matched. expected:{} current:{}", stackId, stackFrameId);
            }
        }
        if (currentStackFrame instanceof RootStackFrame) {
            logSpan(((RootStackFrame) currentStackFrame).getSpan());
        } else {
            logSpan(((SpanEventStackFrame) currentStackFrame).getSpanEvent());
        }
    }

    public StackFrame getCurrentStackFrame() {
        return callStack.getCurrentStackFrame();
    }

    /**
     * Get current TraceID. If it was not set this will return null.                                                         
     *
     * @return
     */
    @Override
    public TraceId getTraceId() {
        return callStack.getSpan().getTraceId();
    }

    public boolean canSampled() {
        return this.sampling;
    }

    public void setSampling(boolean sampling) {
        this.sampling = sampling;
    }

    private void logSpan(SpanEvent spanEvent) {
        if (isTrace) {
            final Thread th = Thread.currentThread();
            logger.trace("[WRITE SpanEvent]{} Thread ID={} Name={}", spanEvent, th.getId(), th.getName());
        }
        this.storage.store(spanEvent);
    }

    private void logSpan(Span span) {
        if (isTrace) {
            final Thread th = Thread.currentThread();
            logger.trace("[WRITE SpanEvent]{} Thread ID={} Name={}", span, th.getId(), th.getName());
        }
        this.storage.store(span);
    }

    @Override
    public void recordException(Object result) {
        if (result instanceof Throwable) {
            Throwable th = (Throwable) result;
            String drop = StringUtils.drop(th.getMessage(), 256);
            recordAttribute(AnnotationKey.EXCEPTION, drop);

            Span span = getCallStack().getSpan();
            if (span.getException() == 0) {
                span.setException(1);
            }
        }
    }

    @Override
    public void recordApi(MethodDescriptor methodDescriptor) {
        if (methodDescriptor == null) {
            return;
        }
        if (methodDescriptor.getApiId() == 0) {
            recordAttribute(AnnotationKey.API, methodDescriptor.getFullName());
        } else {
            recordAttribute(AnnotationKey.API_DID, methodDescriptor.getApiId());
        }
    }

    @Override
    public void recordApi(MethodDescriptor methodDescriptor, Object[] args) {
        // API 저장 방법의 개선 필요.                                                                                        
        recordApi(methodDescriptor);
        recordArgs(args);
    }

    @Override
    public void recordApi(MethodDescriptor methodDescriptor, Object[] args, int start, int end) {
        recordApi(methodDescriptor);
        recordArgs(args, start, end);
    }

    @Override
    public void recordApi(int apiId) {
        recordAttribute(AnnotationKey.API_ID, apiId);
    }

    @Override
    public void recordApi(int apiId, Object[] args) {
        recordAttribute(AnnotationKey.API_ID, apiId);
        recordArgs(args);
    }

    @Override
    public void recordApi(int apiId, Object[] args, int start, int end) {
        recordAttribute(AnnotationKey.API_ID, apiId);
        recordArgs(args);
    }

    private void recordArgs(Object[] args, int start, int end) {
        if (args != null) {
            int max = Math.min(Math.min(args.length, AnnotationKey.MAX_ARGS_SIZE), end);
            for (int i = start; i < max; i++) {
                recordAttribute(AnnotationKey.getArgs(i), args[i]);
            }
            // TODO MAX 사이즈를 넘는건 마크만 해줘야 하나?
        }
    }

    private void recordArgs(Object[] args) {
        if (args != null) {
            int max = Math.min(args.length, AnnotationKey.MAX_ARGS_SIZE);
            for (int i = 0; i < max; i++) {
                recordAttribute(AnnotationKey.getArgs(i), args[i]);
            }
            // TODO MAX 사이즈를 넘는건 마크만 해줘야 하나?                                                                  
        }
    }


    @Override
    public void recordAttribute(final AnnotationKey key, final String value) {
        recordAttribute(key, (Object) value);
    }

    @Override
    public ParsingResult recordSqlInfo(String sql) {
        if (sql == null) {
            return null;
        }
        ParsingResult parsingResult = traceContext.parseSql(sql);
        recordSqlParsingResult(parsingResult);
        return parsingResult;
    }

    @Override
    public void recordSqlParsingResult(ParsingResult parsingResult) {
        if (parsingResult == null) {
            return;
        }
        String sql = parsingResult.getSql();
        recordAttribute(AnnotationKey.SQL_ID, sql.hashCode());
        String output = parsingResult.getOutput();
        if (output != null && output.length() != 0) {
            recordAttribute(AnnotationKey.SQL_PARAM, output);
        }
    }

    @Override
    public void recordAttribute(final AnnotationKey key, final Object value) {
        // TODO API 단일화 필요.                                                                                             
        final StackFrame currentStackFrame = this.currentStackFrame;
        if (currentStackFrame instanceof RootStackFrame) {
            Span span = ((RootStackFrame) currentStackFrame).getSpan();
            span.addAnnotation(new Annotation(key, value));
        } else {
            SpanEvent spanEvent = ((SpanEventStackFrame) currentStackFrame).getSpanEvent();
            spanEvent.addAnnotation(new Annotation(key, value));
        }

    }


    @Override
    public void recordServiceType(final ServiceType serviceType) {
        // TODO API 단일화 필요.                                                                                             
        StackFrame currentStackFrame = this.currentStackFrame;
        if (currentStackFrame instanceof RootStackFrame) {
            Span span = ((RootStackFrame) currentStackFrame).getSpan();
            span.setServiceType(serviceType.getCode());
        } else {
            SpanEvent spanEvent = ((SpanEventStackFrame) currentStackFrame).getSpanEvent();
            spanEvent.setServiceType(serviceType.getCode());
        }

    }

    @Override
    public void recordRpcName(final String rpc) {
        // TODO API 단일화 필요.                                                                                             
        StackFrame currentStackFrame = this.currentStackFrame;
        if (currentStackFrame instanceof RootStackFrame) {
            Span span = ((RootStackFrame) currentStackFrame).getSpan();
            span.setRpc(rpc);
        } else {
            SpanEvent spanEvent = ((SpanEventStackFrame) currentStackFrame).getSpanEvent();
            spanEvent.setRpc(rpc);
        }

    }

    @Override
    public void recordDestinationId(final String destinationId) {
        // TODO API 단일화 필요.                                                                                             
        StackFrame currentStackFrame = this.currentStackFrame;
        if (currentStackFrame instanceof SpanEventStackFrame) {
            SpanEvent spanEvent = ((SpanEventStackFrame) currentStackFrame).getSpanEvent();
            spanEvent.setDestinationId(destinationId);
        }
    }

    @Override
    @Deprecated
    public void recordDestinationAddress(List<String> address) {
        // TODO API 단일화 필요.                                                                                             
//        StackFrame currentStackFrame = this.currentStackFrame;
//        if (currentStackFrame instanceof SpanEventStackFrame) {
//            SpanEvent spanEvent = ((SpanEventStackFrame) currentStackFrame).getSpanEvent();
//            spanEvent.setDestinationAddress();
//        }
    }

    @Override
    public void recordDestinationAddressList(List<String> addressList) {
        //To change body of created methods use File | Settings | File Templates.                                            
    }

    @Override
    public void recordEndPoint(final String endPoint) {
        // TODO API 단일화 필요.                                                                                             
        StackFrame currentStackFrame = this.currentStackFrame;
        if (currentStackFrame instanceof RootStackFrame) {
            Span span = ((RootStackFrame) currentStackFrame).getSpan();
            span.setEndPoint(endPoint);
        } else {
            SpanEvent spanEvent = ((SpanEventStackFrame) currentStackFrame).getSpanEvent();
            spanEvent.setEndPoint(endPoint);
        }
    }

    @Override
    public void recordRemoteAddr(final String remoteAddr) {
        // TODO API 단일화 필요.
        StackFrame currentStackFrame = this.currentStackFrame;
        if (currentStackFrame instanceof RootStackFrame) {
            Span span = ((RootStackFrame) currentStackFrame).getSpan();
            span.setRemoteAddr(remoteAddr);
        } else {
            // do nothing.
        }
    }

    @Override
    public void recordNextSpanId(int nextSpanId) {
        StackFrame currentStackFrame = this.currentStackFrame;
        if (currentStackFrame instanceof RootStackFrame) {
            logger.warn("OMG. Something's going wrong. Current stackframe is root Span. nextSpanId={}", nextSpanId);
        } else {
            SpanEvent spanEvent = ((SpanEventStackFrame) currentStackFrame).getSpanEvent();
            if (nextSpanId != -1) {
                spanEvent.setNextSpanId(nextSpanId);
            }
        }
    }

    private void annotate(final AnnotationKey key) {
        StackFrame currentStackFrame = this.currentStackFrame;
        if (currentStackFrame instanceof RootStackFrame) {
            Span span = ((RootStackFrame) currentStackFrame).getSpan();
            span.addAnnotation(new Annotation(key));
        } else {
            SpanEvent spanEvent = ((SpanEventStackFrame) currentStackFrame).getSpanEvent();
            spanEvent.addAnnotation(new Annotation(key));
        }

    }

    @Override
    public void recordParentApplication(String parentApplicationName, short parentApplicationType) {
        StackFrame currentStackFrame = this.currentStackFrame;
        if (currentStackFrame instanceof RootStackFrame) {
            Span span = ((RootStackFrame) currentStackFrame).getSpan();
            span.setParentApplicationName(parentApplicationName);
            span.setParentApplicationType(parentApplicationType);
            if (isDebug) {
                logger.debug("ParentApplicationName marked. parentApplicationName={}", parentApplicationName);
            }
        } else {
            // do nothing.
        }
    }

    @Override
    public void recordAcceptorHost(String host) {
        StackFrame currentStackFrame = this.currentStackFrame;
        if (currentStackFrame instanceof RootStackFrame) {
            Span span = ((RootStackFrame) currentStackFrame).getSpan();
            span.setAcceptorHost(host); // me
            if (isDebug) {
                logger.debug("Acceptor host received. host={}", host);
            }
        } else {
            // do nothing.
        }
    }

    @Override
    public int getStackFrameId() {
        return this.getCurrentStackFrame().getStackFrameId();

    }
}                                                                                                                            
