package com.tstd2.soa.remoting.netty.client;

import com.tstd2.soa.config.Protocol;
import com.tstd2.soa.remoting.netty.MessageCodecConstant;
import com.tstd2.soa.remoting.netty.serialize.RpcSerializeFrame;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

public class NettyClientUtil {

    /**
     * netty channel池
     */
    private volatile static NettyChannelPool nettyChannelPool;

    /**
     * 利用管道发送消息
     */
    public static void writeAndFlush(Protocol protocol, Object request) throws Exception {

        if (nettyChannelPool == null) {
            synchronized (NettyClientUtil.class) {
                if (nettyChannelPool == null) {
                    nettyChannelPool = new NettyChannelPool(2);
                }
            }
        }

        Channel channel = nettyChannelPool.syncGetChannel(protocol, new NettyChannelPool.ConnectCall() {

            @Override
            public Channel connect(Protocol protocol) throws Exception {
                return connectToServer(protocol);
            }
        });

        channel.writeAndFlush(request).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                // System.out.println("RPC Client Send request sessionId:" + request.getSessionId());
            }
        });
    }

    /**
     * 连接服务端
     */
    private static Channel connectToServer(final Protocol protocol) throws InterruptedException {
        // 异步调用
        // 基于NIO的非阻塞实现并行调用，客户端不需要启动多线程即可完成并行调用多个远程服务，相对多线程开销较小
        // 构建RpcProxyHandler异步处理响应的Handler
        final NettyClientInHandler nettyClientInHandler = new NettyClientInHandler();

        // netty
        EventLoopGroup group = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, MessageCodecConstant.MESSAGE_LENGTH, 0, MessageCodecConstant.MESSAGE_LENGTH));
                        pipeline.addLast(new LengthFieldPrepender(MessageCodecConstant.MESSAGE_LENGTH));
                        RpcSerializeFrame.select(protocol.getSerialize(), pipeline);
                        pipeline.addLast(nettyClientInHandler);

                    }
                });

        ChannelFuture future = bootstrap.connect(protocol.getHost(), Integer.parseInt(protocol.getPort()));
        Channel channel = future.sync().channel();

        return channel;
    }

}
