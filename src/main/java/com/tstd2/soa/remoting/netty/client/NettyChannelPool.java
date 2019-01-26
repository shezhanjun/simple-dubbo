package com.tstd2.soa.remoting.netty.client;

import com.tstd2.soa.remoting.netty.serialize.RpcSerializeFrame;
import com.tstd2.soa.rpc.loadbalance.NodeInfo;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class NettyChannelPool {

    /**
     * 默认为每一个ip端口建议一个长连接
     * 单个长连接不适合大对象传输
     */
    private volatile Map<String, Channel[]> channelMap = new ConcurrentHashMap<>();

    private int connections = 1;

    /**
     * 同步获取netty channel
     */
    public Channel syncGetChannel(NodeInfo nodeInfo) throws InterruptedException {

        // 取出对应ip port的channel
        String host = nodeInfo.getHost() + ":" + nodeInfo.getPort();
        Channel[] channels = channelMap.get(host);

        if (channels == null) {
            synchronized (host.intern()) {
                if (channelMap.get(host) == null) {
                    channelMap.put(host, new Channel[connections]);
                }
            }
        }

        // 随机取出一个链接
        int index = connections == 1 ? 0 : new Random().nextInt(connections);
        Channel channel = channelMap.get(host)[index];

        // 如果能获取到,直接返回
        if (channel != null && channel.isActive()) {
            return channel;
        }

        synchronized (host.intern()) {
            // 这里必须再次做判断,当锁被释放后，之前等待的线程已经可以直接拿到结果了。
            if (channel != null && channel.isActive()) {
                return channel;
            }
            // 开始跟服务端交互，获取channel
            channel = connectToServer(nodeInfo);

            channelMap.get(host)[index] = channel;
        }

        return channel;
    }

    private Channel connectToServer(final NodeInfo nodeInfo) throws InterruptedException {
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
                        pipeline.addLast(new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4));
                        pipeline.addLast(new LengthFieldPrepender(4));
                        RpcSerializeFrame.select(nodeInfo.getSerialize(), pipeline);
                        pipeline.addLast(nettyClientInHandler);

                    }
                });

        ChannelFuture future = bootstrap.connect(nodeInfo.getHost(), Integer.parseInt(nodeInfo.getPort()));
        Channel channel = future.sync().channel();

        return channel;
    }
}