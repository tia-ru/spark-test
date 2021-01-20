package tia.test.spark.sentinel;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.adapter.grpc.SentinelGrpcServerInterceptor;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <br/>Patch of SentinelGrpcClientInterceptor from
 *
 * <br/> &lt;groupId&gt; com.alibaba.csp &lt;/groupId&gt;
 * <br/> &lt;artifactId&gt;sentinel-grpc-adapter &lt;/artifactId&gt;
 * <br/> &lt;version&gt; 1.8.0 &lt;/version&gt;
 * <br/>that
 * <br/> 1) throws {@link #FLOW_CONTROL_BLOCK} compatible with Sentinel {@link BlockException#isBlockException}
 * <br/> 2) Extract method {@link #getResourceName} to allow override resource name
 * <br/> 3) Assign service name as resource name instead of service method name
 * <p/>
 * <p>gRPC client interceptor for Sentinel. Currently it only works with unary methods.</p>
 * <p>
 * Example code:
 * <pre>
 * public class ServiceClient {
 *
 *     private final ManagedChannel channel;
 *
 *     ServiceClient(String host, int port) {
 *         this.channel = ManagedChannelBuilder.forAddress(host, port)
 *             .intercept(new SentinelGrpcClientInterceptor()) // Add the client interceptor.
 *             .build();
 *         // Init your stub here.
 *     }
 *
 * }
 * </pre>
 * <p>
 * For server interceptor, see {@link SentinelGrpcServerInterceptor}.
 *
 * @author Eric Zhao, Ilya Tugushev
 */
public class SentinelGrpcClientInterceptor2 implements ClientInterceptor {
    private static final Status FLOW_CONTROL_BLOCK = Status.UNAVAILABLE.withDescription(
            "Flow control limit exceeded (client side)")
            .withCause(BlockException.THROW_OUT_EXCEPTION);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> methodDescriptor,
                                                               CallOptions callOptions, Channel channel) {
        String fullMethodName = getResourceName(methodDescriptor, channel);

        Entry entry = null;
        try {
            entry = SphU.asyncEntry(fullMethodName, EntryType.OUT);
            final AtomicReference<Entry> atomicReferenceEntry = new AtomicReference<>(entry);
            // Allow access, forward the call.
            return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    channel.newCall(methodDescriptor, callOptions)) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                        @Override
                        public void onClose(Status status, Metadata trailers) {
                            Entry entry = atomicReferenceEntry.get();
                            if (entry != null) {
                                // Record the exception metrics.
                                if (!status.isOk()) {
                                    Tracer.traceEntry(status.asRuntimeException(), entry);
                                }
                                entry.exit();
                                atomicReferenceEntry.set(null);
                            }
                            super.onClose(status, trailers);
                        }
                    }, headers);
                }

                /**
                 * Some Exceptions will only call cancel.
                 */
                @Override
                public void cancel(@Nullable String message, @Nullable Throwable cause) {
                    Entry entry = atomicReferenceEntry.get();
                    // Some Exceptions will call onClose and cancel.
                    if (entry != null) {
                        // Record the exception metrics.
                        Tracer.traceEntry(cause, entry);
                        entry.exit();
                        atomicReferenceEntry.set(null);
                    }
                    super.cancel(message, cause);
                }
            };
        } catch (BlockException e) {
            // Flow control threshold exceeded, block the call.
            return new ClientCall<ReqT, RespT>() {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    responseListener.onClose(FLOW_CONTROL_BLOCK, new Metadata());
                }

                @Override
                public void request(int numMessages) {
                }

                @Override
                public void cancel(@Nullable String message, @Nullable Throwable cause) {
                }

                @Override
                public void halfClose() {
                }

                @Override
                public void sendMessage(ReqT message) {
                }
            };
        } catch (RuntimeException e) {
            // Catch the RuntimeException newCall throws, entry is guaranteed to exit.
            if (entry != null) {
                Tracer.traceEntry(e, entry);
                entry.exit();
            }
            throw e;
        }
    }

    /**
     * Allows override resource name extraction strategy in subclasses.
     */
    protected <ReqT, RespT> String getResourceName(MethodDescriptor<ReqT, RespT> methodDescriptor, Channel channel) {
        return methodDescriptor.getServiceName();
    }
}
