/**
 * File Created at 2011-12-05
 * $Id$
 *
 * Copyright 2008 Alibaba.com Croporation Limited.
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of
 * Alibaba Company. ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Alibaba.com.
 */
package com.alibaba.dubbo.rpc.protocol.thrift;


import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang.StringUtils;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TIOStreamTransport;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.AtomicPositiveInteger;
import com.alibaba.dubbo.common.utils.ClassHelper;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.Codec2;
import com.alibaba.dubbo.remoting.buffer.ChannelBuffer;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferInputStream;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.protocol.thrift.io.RandomAccessByteArrayOutputStream;

/**
 * Thrift framed protocol codec.
 *
 * <pre>
 * |<-                                  message header                                  ->|<- message body ->|
 * +----------------+----------------------+------------------+---------------------------+------------------+
 * | magic (2 bytes)|message size (4 bytes)|head size(2 bytes)| version (1 byte) | header |   message body   |
 * +----------------+----------------------+------------------+---------------------------+------------------+
 * |<-                                               message size                                          ->|
 * </pre>
 *
 * <p>
 * <b>header fields in version 1</b>
 * <ol>
 *     <li>string - service name</li>
 *     <li>long   - dubbo request id</li>
 * </ol>
 * </p>
 *
 * @author <a href="mailto:gang.lvg@alibaba-inc.com">gang.lvg</a>
 */
public class ThriftCodec implements Codec2 {

    private static final AtomicInteger THRIFT_SEQ_ID = new AtomicInteger( 0 );

    private static final AtomicPositiveInteger cachedId = new AtomicPositiveInteger(0);

    // XXX 这个缓存的Map没有过期策略
    private static final ConcurrentMap<String, Class<?>> cachedClass =
            new ConcurrentHashMap<String, Class<?>>();

    // XXX 这个缓存的Map没有过期策略,请求越来越多，这个Map越长越大
    // XXX 当多个client 使用相同的request_id时，从这个Map获取数据时，有可能发生获取了错误的数据
    static final ConcurrentMap<Long, RequestData> cachedRequest =
            new ConcurrentHashMap<Long, RequestData>();

    public static final int MESSAGE_LENGTH_INDEX = 2;

    public static final int MESSAGE_HEADER_LENGTH_INDEX = 6;

    public static final int MESSAGE_SHORTEST_LENGTH = 10;

    public static final String NAME = "thrift";

    public static final String PARAMETER_CLASS_NAME_GENERATOR = "class.name.generator";

    public static final byte VERSION = (byte)1;

    public static final short MAGIC = (short) 0xdabc;

    public void encode( Channel channel, ChannelBuffer buffer, Object message )
            throws IOException {

        if ( message instanceof Request ) {
            encodeRequest( channel, buffer, ( Request ) message );
        }
        else if ( message instanceof Response ) {
            encodeResponse( channel, buffer, ( Response ) message );
        } else {
            throw new UnsupportedOperationException(
                    new StringBuilder( 32 )
                            .append( "Thrift codec only support encode " )
                            .append( Request.class.getName() )
                            .append( " and " )
                            .append( Response.class.getName() )
                            .toString() );
        }

    }

    public Object decode( Channel channel, ChannelBuffer buffer ) throws IOException {

        int available = buffer.readableBytes();

        if ( available < MESSAGE_SHORTEST_LENGTH ) {

            return DecodeResult.NEED_MORE_INPUT;

        } else {

            TIOStreamTransport transport = new TIOStreamTransport( new ChannelBufferInputStream(buffer));

            TBinaryProtocol protocol = new TBinaryProtocol( transport );

            short magic;
            int messageLength;

            try{
//                protocol.readI32(); // skip the first message length
                byte[] bytes = new byte[4];
                transport.read( bytes, 0, 4 );
                magic = protocol.readI16();
                messageLength = protocol.readI32();

            } catch ( TException e ) {
                throw new IOException( e.getMessage(), e );
            }

            if ( MAGIC != magic ) {
                throw new IOException(
                        new StringBuilder( 32 )
                                .append( "Unknown magic code " )
                                .append( magic )
                                .toString() );
            }

            if ( available < messageLength ) { return  DecodeResult.NEED_MORE_INPUT; }

            return decode( protocol );

        }

    }

    private Object decode( TProtocol protocol )
            throws IOException {

        // version
        String serviceName;
        long id;

        TMessage message;

        try {
            protocol.readI16();
            protocol.readByte();
            serviceName = protocol.readString();
            id = protocol.readI64();
            message = protocol.readMessageBegin();
        } catch ( TException e ) {
            throw new IOException( e.getMessage(), e );
        }

        if ( message.type == TMessageType.CALL ) {

            RpcInvocation result = new RpcInvocation();
            result.setAttachment(Constants.INTERFACE_KEY, serviceName );
            result.setMethodName( message.name );

            String argsClassName = ExtensionLoader.getExtensionLoader(ClassNameGenerator.class)
                    .getExtension(ThriftClassNameGenerator.NAME).generateArgsClassName( serviceName, message.name );

            if ( StringUtils.isEmpty( argsClassName ) ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION,
                                        "The specified interface name incorrect." );
            }

            Class clazz = cachedClass.get( argsClassName );

            if ( clazz == null ) {
                try {

                    clazz = ClassHelper.forNameWithThreadContextClassLoader( argsClassName );

                    cachedClass.putIfAbsent( argsClassName, clazz );

                } catch ( ClassNotFoundException e ) {
                    throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                }
            }

            TBase args;

            try {
                args = ( TBase ) clazz.newInstance();
            } catch ( InstantiationException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            } catch ( IllegalAccessException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            }

            try{
                args.read( protocol );
                protocol.readMessageEnd();
            } catch ( TException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            }

            List<Object> parameters = new ArrayList<Object>();
            List<Class<?>> parameterTypes =new ArrayList<Class<?>>();
            int index = 1;

            while ( true ) {

                TFieldIdEnum fieldIdEnum = args.fieldForId( index++ );

                if ( fieldIdEnum == null ) { break; }

                String fieldName = fieldIdEnum.getFieldName();

                String getMethodName = ThriftUtils.generateGetMethodName( fieldName );

                Method getMethod;

                try {
                    getMethod = clazz.getMethod( getMethodName );
                } catch ( NoSuchMethodException e ) {
                	// try as a boolean type field
                	String boolGetMethodName = ThriftUtils.generateGetBooleanMethodName(fieldName);
                	try{
                		getMethod = clazz.getMethod(boolGetMethodName);
                	}catch (NoSuchMethodException e2) {
                		 throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
					}
                }

                parameterTypes.add( getMethod.getReturnType() );
                try {
                    parameters.add( getMethod.invoke( args ) );
                } catch ( IllegalAccessException e ) {
                    throw new RpcException(
                            RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                } catch ( InvocationTargetException e ) {
                    throw new RpcException(
                            RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                }

            }

            result.setArguments( parameters.toArray() );
            result.setParameterTypes(parameterTypes.toArray(new Class[parameterTypes.size()]));

            // 每次生成一个id 放入缓存中
            int cacheReqId = cachedId.getAndIncrement();
            Request request = new Request( cacheReqId );
            request.setData( result );

            cachedRequest.putIfAbsent( new Long(cacheReqId), RequestData.create(id, message.seqid, serviceName, message.name ) );

            return request;

        } else if ( message.type == TMessageType.EXCEPTION ) {

            TApplicationException exception;

            try {
                exception = TApplicationException.read( protocol );
                protocol.readMessageEnd();
            } catch ( TException e ) {
                throw new IOException( e.getMessage(), e );
            }

            RpcResult result = new RpcResult();

            result.setException( new RpcException( exception.getMessage() ) );

            Response response = new Response();

            response.setResult( result );

            response.setId( id );

            return response;

        } else if ( message.type == TMessageType.REPLY ) {

            String resultClassName = ExtensionLoader.getExtensionLoader( ClassNameGenerator.class )
                    .getExtension(ThriftClassNameGenerator.NAME).generateResultClassName( serviceName, message.name );

            if ( StringUtils.isEmpty( resultClassName ) ) {
                throw new IllegalArgumentException(
                        new StringBuilder( 32 )
                                .append( "Could not infer service result class name from service name " )
                                .append( serviceName )
                                .append( ", the service name you specified may not generated by thrift idl compiler" )
                                .toString() );
            }

            Class<?> clazz = cachedClass.get( resultClassName );

            if ( clazz == null ) {

                try {

                    clazz = ClassHelper.forNameWithThreadContextClassLoader( resultClassName );

                    cachedClass.putIfAbsent( resultClassName, clazz );

                } catch ( ClassNotFoundException e ) {
                    throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                }

            }

            TBase<?,? extends TFieldIdEnum> result;
            try {
                result = ( TBase<?,?> ) clazz.newInstance();
            } catch ( InstantiationException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            } catch ( IllegalAccessException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            }

            try {
                result.read( protocol );
                protocol.readMessageEnd();
            } catch ( TException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            }

            Object realResult = null;

            int index = 0;

            while ( true ) {

                TFieldIdEnum fieldIdEnum = result.fieldForId( index++ );

                if ( fieldIdEnum == null ) { break ; }

                Field field;

                try {
                    field = clazz.getDeclaredField( fieldIdEnum.getFieldName() );
                    field.setAccessible( true );
                } catch ( NoSuchFieldException e ) {
                    throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                }

                try {
                    realResult = field.get( result );
                } catch ( IllegalAccessException e ) {
                    throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                }

                if ( realResult != null ) { break ; }

            }

            Response response = new Response();

            response.setId( id );

            RpcResult rpcResult = new RpcResult();

            if ( realResult instanceof Throwable ) {
                rpcResult.setException( ( Throwable ) realResult );
            } else {
                rpcResult.setValue(realResult);
            }

            response.setResult( rpcResult );

            return response;

        } else {
            // Impossible
            throw new IOException(  );
        }

    }

    private void encodeRequest( Channel channel, ChannelBuffer buffer, Request request )
            throws IOException {

        RpcInvocation inv = ( RpcInvocation ) request.getData();

        int seqId = nextSeqId();

        String serviceName = inv.getAttachment(Constants.INTERFACE_KEY);

        if ( StringUtils.isEmpty( serviceName ) ) {
            throw new IllegalArgumentException(
                    new StringBuilder( 32 )
                            .append( "Could not find service name in attachment with key " )
                            .append(Constants.INTERFACE_KEY)
                            .toString() );
        }

        TMessage message = new TMessage(
                inv.getMethodName(),
                TMessageType.CALL,
                seqId );

        String methodArgs = ExtensionLoader.getExtensionLoader( ClassNameGenerator.class )
            .getExtension(channel.getUrl().getParameter(ThriftConstants.CLASS_NAME_GENERATOR_KEY, ThriftClassNameGenerator.NAME))
            .generateArgsClassName(serviceName, inv.getMethodName());

        if ( StringUtils.isEmpty( methodArgs ) ) {
            throw new RpcException( RpcException.SERIALIZATION_EXCEPTION,
                                    new StringBuilder(32).append(
                                            "Could not encode request, the specified interface may be incorrect." ).toString() );
        }

        Class<?> clazz = cachedClass.get( methodArgs );

        if ( clazz == null ) {

            try {

                clazz = ClassHelper.forNameWithThreadContextClassLoader( methodArgs );

                cachedClass.putIfAbsent( methodArgs, clazz );

            } catch ( ClassNotFoundException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            }

        }

        TBase args;

        try {
            args = (TBase) clazz.newInstance();
        } catch ( InstantiationException e ) {
            throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
        } catch ( IllegalAccessException e ) {
            throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
        }

        for( int i = 0; i < inv.getArguments().length; i++ ) {

            Object obj = inv.getArguments()[i];

            if ( obj == null ) { continue; }

            TFieldIdEnum field = args.fieldForId( i + 1 );

            String setMethodName = ThriftUtils.generateSetMethodName( field.getFieldName() );

            Method method;

            try {
                method = clazz.getMethod( setMethodName, inv.getParameterTypes()[i] );
            } catch ( NoSuchMethodException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            }

            try {
                method.invoke( args, obj );
            } catch ( IllegalAccessException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            } catch ( InvocationTargetException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            }

        }

        RandomAccessByteArrayOutputStream bos = new RandomAccessByteArrayOutputStream( 1024 );

        TIOStreamTransport transport = new TIOStreamTransport( bos );

        TBinaryProtocol protocol = new TBinaryProtocol( transport );

        int headerLength, messageLength;

        byte[] bytes = new byte[4];
        try {
            // magic
            protocol.writeI16( MAGIC );
            // message length placeholder
            protocol.writeI32( Integer.MAX_VALUE );
            // message header length placeholder
            protocol.writeI16( Short.MAX_VALUE );
            // version
            protocol.writeByte( VERSION );
            // service name
            protocol.writeString( serviceName );
            // dubbo request id
            protocol.writeI64( request.getId() );
            protocol.getTransport().flush();
            // header size
            headerLength = bos.size();

            // message body
            protocol.writeMessageBegin( message );
            args.write( protocol );
            protocol.writeMessageEnd();
            protocol.getTransport().flush();
            int oldIndex = messageLength = bos.size();

            // fill in message length and header length
            try {
                TFramedTransport.encodeFrameSize( messageLength, bytes );
                bos.setWriteIndex( MESSAGE_LENGTH_INDEX );
                protocol.writeI32( messageLength );
                bos.setWriteIndex( MESSAGE_HEADER_LENGTH_INDEX );
                protocol.writeI16( ( short )( 0xffff & headerLength ) );
            } finally {
                bos.setWriteIndex( oldIndex );
            }

        } catch ( TException e ) {
            throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
        }

        buffer.writeBytes(bytes);
        buffer.writeBytes(bos.toByteArray());

    }

    @SuppressWarnings({ "unchecked", "deprecation" })
	private void encodeResponse( Channel channel, ChannelBuffer buffer, Response response )
            throws IOException {

        RpcResult result = ( RpcResult ) response.getResult();

        RequestData rd = cachedRequest.get( response.getId() );//        cachedRequest.remove(response.getId());// XXX 移除缓存的东东，防止 OOM
        // 把原来的reqId 恢复回来
        response.setId(rd.reqId);



        String resultClassName = ExtensionLoader.getExtensionLoader( ClassNameGenerator.class ).getExtension(
                    channel.getUrl().getParameter(ThriftConstants.CLASS_NAME_GENERATOR_KEY, ThriftClassNameGenerator.NAME))
                    .generateResultClassName(rd.serviceName, rd.methodName);

        if ( StringUtils.isEmpty( resultClassName ) ) {
            throw new RpcException( RpcException.SERIALIZATION_EXCEPTION,
                                    new StringBuilder( 32 ).append(
                                            "Could not encode response, the specified interface may be incorrect." ).toString() );
        }

        Class clazz = cachedClass.get( resultClassName );

        if ( clazz == null ) {

            try {
                clazz = ClassHelper.forNameWithThreadContextClassLoader(resultClassName);
                cachedClass.putIfAbsent( resultClassName, clazz );
            } catch ( ClassNotFoundException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            }

        }

        TBase resultObj;

        try {
            resultObj = ( TBase ) clazz.newInstance();
        } catch ( InstantiationException e ) {
            throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
        } catch ( IllegalAccessException e ) {
            throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
        }

        TApplicationException applicationException = null;
        TMessage message;

        if ( result.hasException() ) {
            Throwable throwable = result.getException();
            int index = 1;
            boolean found = false;
            while ( true ) {
                TFieldIdEnum fieldIdEnum = resultObj.fieldForId( index++ );
                if ( fieldIdEnum == null ) { break; }
                String fieldName = fieldIdEnum.getFieldName();
                String getMethodName = ThriftUtils.generateGetMethodName( fieldName );
                String setMethodName = ThriftUtils.generateSetMethodName( fieldName );
                Method getMethod;
                Method setMethod;
                try {
                	try{
                		getMethod = clazz.getMethod( getMethodName );
                	}catch (NoSuchMethodException e) {
                    	// try as a boolean type field
                    	String boolGetMethodName = ThriftUtils.generateGetBooleanMethodName(fieldName);
                    	getMethod = clazz.getMethod(boolGetMethodName);
					}

                    if ( getMethod.getReturnType().equals( throwable.getClass() ) ) {
                        found = true;
                        setMethod = clazz.getMethod( setMethodName, throwable.getClass() );
                        setMethod.invoke( resultObj, throwable );
                    }
                } catch ( NoSuchMethodException e ) {
                    throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                } catch ( InvocationTargetException e ) {
                    throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                } catch ( IllegalAccessException e ) {
                    throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                }
            }

            if ( !found ) {
                applicationException = new TApplicationException( throwable.getMessage() );
            }

        } else {
            Object realResult = result.getResult();
            // result field id is 0
            TFieldIdEnum field = resultObj.fieldForId( 0 );
            if(field != null){ // 返回值不为void
	            String fieldName  = field.getFieldName();
	            String setMethodName = ThriftUtils.generateSetMethodName( fieldName );
	            String getMethodName = ThriftUtils.generateGetMethodName( fieldName );
	            Method getMethod;
	            Method setMethod;
	            try {
	            	try{
	            		getMethod = clazz.getMethod( getMethodName );
	            	}catch (NoSuchMethodException e) {
	                	String boolGetMethodName = ThriftUtils.generateGetBooleanMethodName(fieldName);
	                	getMethod = clazz.getMethod(boolGetMethodName);
					}
	                setMethod = clazz.getMethod( setMethodName, getMethod.getReturnType() );
	                setMethod.invoke( resultObj, realResult );
	            } catch ( NoSuchMethodException e ) {
	                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
	            } catch ( InvocationTargetException e ) {
	                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
	            } catch ( IllegalAccessException e ) {
	                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
	            }
	        }//end if
        }

        if ( applicationException != null ) {
            message = new TMessage( rd.methodName, TMessageType.EXCEPTION, rd.id );
        } else {
            message = new TMessage( rd.methodName, TMessageType.REPLY, rd.id );
        }

        RandomAccessByteArrayOutputStream bos = new RandomAccessByteArrayOutputStream( 1024 );

        TIOStreamTransport transport = new TIOStreamTransport( bos );

        TBinaryProtocol protocol = new TBinaryProtocol( transport );

        int messageLength;
        int headerLength;

        byte[] bytes = new byte[4];
        try {
            // magic
            protocol.writeI16( MAGIC );
            // message length
            protocol.writeI32( Integer.MAX_VALUE );
            // message header length
            protocol.writeI16( Short.MAX_VALUE );
            // version
            protocol.writeByte( VERSION );
            // service name
            protocol.writeString( rd.serviceName );
            // id
            protocol.writeI64( response.getId() );
            protocol.getTransport().flush();
            headerLength = bos.size();

            // message
            protocol.writeMessageBegin( message );
            switch ( message.type ) {
                case TMessageType.EXCEPTION:
                    applicationException.write( protocol );
                    break;
                case TMessageType.REPLY:
                    resultObj.write( protocol );
                    break;
            }
            protocol.writeMessageEnd();
            protocol.getTransport().flush();
            int oldIndex = messageLength = bos.size();

            try{
                TFramedTransport.encodeFrameSize( messageLength, bytes );
                bos.setWriteIndex( MESSAGE_LENGTH_INDEX );
                protocol.writeI32( messageLength );
                bos.setWriteIndex( MESSAGE_HEADER_LENGTH_INDEX );
                protocol.writeI16( ( short ) ( 0xffff & headerLength ) );
            } finally {
                bos.setWriteIndex( oldIndex );
            }

        } catch ( TException e ) {
            throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
        }

        buffer.writeBytes(bytes);
        buffer.writeBytes(bos.toByteArray());

    }

    private static int nextSeqId() {
        return THRIFT_SEQ_ID.incrementAndGet();
    }

    // just for test
    static int getSeqId() {
        return THRIFT_SEQ_ID.get();
    }

    static class RequestData {
    	long reqId;
        int id;
        String serviceName;
        String methodName;

        static RequestData create( long reqId, int id, String sn, String mn ) {
            RequestData result = new RequestData();
            result.reqId = reqId;
            result.id = id;
            result.serviceName = sn;
            result.methodName = mn;
            return result;
        }

    }

}
