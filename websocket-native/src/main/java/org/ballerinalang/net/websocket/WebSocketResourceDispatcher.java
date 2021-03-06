/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinalang.net.websocket;

import io.ballerina.runtime.api.TypeTags;
import io.ballerina.runtime.api.async.Callback;
import io.ballerina.runtime.api.async.StrandMetadata;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.ArrayType;
import io.ballerina.runtime.api.types.MethodType;
import io.ballerina.runtime.api.types.ResourceMethodType;
import io.ballerina.runtime.api.types.ServiceType;
import io.ballerina.runtime.api.types.StructureType;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.types.XmlNodeType;
import io.ballerina.runtime.api.utils.JsonUtils;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.utils.XmlUtils;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BValue;
import io.ballerina.runtime.api.values.BXml;
import io.ballerina.runtime.observability.ObservabilityConstants;
import io.ballerina.runtime.observability.ObserveUtils;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.CorruptedFrameException;
import org.ballerinalang.net.http.HttpConstants;
import org.ballerinalang.net.http.HttpUtil;
import org.ballerinalang.net.http.ValueCreatorUtils;
import org.ballerinalang.net.transport.contract.websocket.WebSocketBinaryMessage;
import org.ballerinalang.net.transport.contract.websocket.WebSocketCloseMessage;
import org.ballerinalang.net.transport.contract.websocket.WebSocketConnection;
import org.ballerinalang.net.transport.contract.websocket.WebSocketControlMessage;
import org.ballerinalang.net.transport.contract.websocket.WebSocketControlSignal;
import org.ballerinalang.net.transport.contract.websocket.WebSocketHandshaker;
import org.ballerinalang.net.transport.contract.websocket.WebSocketTextMessage;
import org.ballerinalang.net.transport.message.HttpCarbonMessage;
import org.ballerinalang.net.transport.message.HttpCarbonRequest;
import org.ballerinalang.net.websocket.observability.WebSocketObservabilityConstants;
import org.ballerinalang.net.websocket.observability.WebSocketObservabilityUtil;
import org.ballerinalang.net.websocket.observability.WebSocketObserverContext;
import org.ballerinalang.net.websocket.server.OnUpgradeResourceCallback;
import org.ballerinalang.net.websocket.server.WebSocketConnectionInfo;
import org.ballerinalang.net.websocket.server.WebSocketConnectionManager;
import org.ballerinalang.net.websocket.server.WebSocketServerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.ballerinalang.net.websocket.WebSocketConstants.BACK_SLASH;
import static org.ballerinalang.net.websocket.WebSocketConstants.ON_BINARY_METADATA;
import static org.ballerinalang.net.websocket.WebSocketConstants.ON_CLOSE_METADATA;
import static org.ballerinalang.net.websocket.WebSocketConstants.ON_ERROR_METADATA;
import static org.ballerinalang.net.websocket.WebSocketConstants.ON_OPEN_METADATA;
import static org.ballerinalang.net.websocket.WebSocketConstants.ON_PING_METADATA;
import static org.ballerinalang.net.websocket.WebSocketConstants.ON_PONG_METADATA;
import static org.ballerinalang.net.websocket.WebSocketConstants.ON_TEXT_METADATA;
import static org.ballerinalang.net.websocket.WebSocketConstants.ON_TIMEOUT_METADATA;
import static org.ballerinalang.net.websocket.WebSocketConstants.ON_UPGRADE_METADATA;
import static org.ballerinalang.net.websocket.WebSocketConstants.PARAM_TYPE_STRING;
import static org.ballerinalang.net.websocket.WebSocketConstants.RESOURCE_NAME_ON_BINARY;
import static org.ballerinalang.net.websocket.WebSocketConstants.RESOURCE_NAME_ON_CLOSE;
import static org.ballerinalang.net.websocket.WebSocketConstants.RESOURCE_NAME_ON_CONNECT;
import static org.ballerinalang.net.websocket.WebSocketConstants.RESOURCE_NAME_ON_ERROR;
import static org.ballerinalang.net.websocket.WebSocketConstants.RESOURCE_NAME_ON_IDLE_TIMEOUT;
import static org.ballerinalang.net.websocket.WebSocketConstants.RESOURCE_NAME_ON_PING;
import static org.ballerinalang.net.websocket.WebSocketConstants.RESOURCE_NAME_ON_PONG;
import static org.ballerinalang.net.websocket.WebSocketConstants.RESOURCE_NAME_ON_STRING;

/**
 * {@code WebSocketDispatcher} This is the web socket request dispatcher implementation which finds best matching
 * resource for incoming web socket request.
 *
 * @since 0.94
 */
public class WebSocketResourceDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WebSocketResourceDispatcher.class);

    private WebSocketResourceDispatcher() {
    }

    public static void dispatchUpgrade(WebSocketHandshaker webSocketHandshaker, WebSocketServerService wsService,
            BMap<BString, Object> httpEndpointConfig, WebSocketConnectionManager connectionManager) {
        ResourceMethodType resourceFunction = ((ServiceType) wsService.getBalService().getType())
                .getResourceMethods()[0];
        String[] resourceParams = resourceFunction.getResourcePath();

        BObject httpCaller = ValueCreatorUtils.createCallerObject();
        BObject inRequest = ValueCreatorUtils.createRequestObject();
        BObject inRequestEntity = ValueCreatorUtils.createEntityObject();
        HttpCarbonRequest httpCarbonMessage = webSocketHandshaker.getHttpCarbonRequest();
        String errMsg = "No resource found for path " + httpCarbonMessage.getRequestUrl();
        String subPath = (String) httpCarbonMessage.getProperty(HttpConstants.SUB_PATH);
        String[] subPaths = new String[0];
        ArrayList<String> pathParamArr = new ArrayList<>();
        if (!subPath.isEmpty()) {
            subPath = sanitizeSubPath(subPath).substring(1);
            subPaths = subPath.split(BACK_SLASH);
        }
        if (!resourceParams[0].equals(".")) {
            if (resourceParams.length != subPaths.length) {
                webSocketHandshaker.cancelHandshake(404, errMsg);
                return;
            }
            int i = 0;
            for (String resourceParam : resourceParams) {
                if (resourceParam.equals("*")) {
                    pathParamArr.add(subPaths[i]);
                } else if (!resourceParam.equals(subPaths[i])) {
                    webSocketHandshaker.cancelHandshake(404, errMsg);
                    return;
                }
                i++;
            }
        }
        enrichHttpCallerWithConnectionInfo(httpCaller, httpCarbonMessage, httpEndpointConfig);
        enrichHttpCallerWithNativeData(httpCaller, httpCarbonMessage);
        HttpUtil.populateInboundRequest(inRequest, inRequestEntity, httpCarbonMessage);

        httpCaller.addNativeData(WebSocketConstants.WEBSOCKET_HANDSHAKER, webSocketHandshaker);
        httpCaller.addNativeData(WebSocketConstants.WEBSOCKET_SERVICE, wsService);
        httpCaller.addNativeData(HttpConstants.NATIVE_DATA_WEBSOCKET_CONNECTION_MANAGER, connectionManager);
        Type[] parameterTypes = resourceFunction.getParameterTypes();

        Object[] bValues = new Object[parameterTypes.length * 2];
        int index = 0;
        int pathParamIndex = 0;
        for (Type param : parameterTypes) {
            String typeName = param.getName();
            switch (typeName) {
                case HttpConstants.REQUEST:
                    bValues[index++] = inRequest;
                    bValues[index++] = true;
                    break;
                case PARAM_TYPE_STRING:
                    bValues[index++] = StringUtils.fromString(pathParamArr.get(pathParamIndex++));
                    bValues[index++] = true;
                    break;
                default:
                    break;
            }
        }
        wsService.getRuntime()
                .invokeMethodAsync(wsService.getBalService(), resourceFunction.getName(), null, ON_UPGRADE_METADATA,
                        new OnUpgradeResourceCallback(webSocketHandshaker, wsService, connectionManager), bValues);
    }

    private static String sanitizeSubPath(String subPath) {
        if (BACK_SLASH.equals(subPath)) {
            return subPath;
        }
        if (!subPath.startsWith(BACK_SLASH)) {
            subPath = HttpConstants.DEFAULT_BASE_PATH + subPath;
        }
        subPath = subPath.endsWith(BACK_SLASH) ? subPath.substring(0, subPath.length() - 1) : subPath;
        return subPath;
    }

    public static void enrichHttpCallerWithNativeData(BObject caller, HttpCarbonMessage inboundMsg) {
        caller.addNativeData("transport_message", inboundMsg);
    }

    public static void dispatchOnOpen(WebSocketConnection webSocketConnection, BObject webSocketCaller,
            WebSocketServerService wsService) {
        MethodType onOpenResource = null;
        Object dispatchingService = wsService.getWsService(webSocketConnection.getChannelId());
        MethodType[] remoteFunctions = ((ServiceType) (((BValue) dispatchingService)
                .getType())).getMethods();
        BObject balService = (BObject) dispatchingService;
        for (MethodType remoteFunc : remoteFunctions) {
            if (remoteFunc.getName().equals(RESOURCE_NAME_ON_CONNECT)) {
                onOpenResource = remoteFunc;
                break;
            }
        }
        if (onOpenResource != null) {
            executeOnOpenResource(wsService, balService, onOpenResource, webSocketCaller, webSocketConnection);
        } else {
            webSocketConnection.readNextFrame();
        }
    }

    private static void executeOnOpenResource(WebSocketService wsService, BObject balService,
            MethodType onOpenResource,
            BObject webSocketEndpoint, WebSocketConnection webSocketConnection) {
        Type[] parameterTypes = onOpenResource.getParameterTypes();
        Object[] bValues = new Object[parameterTypes.length * 2];
        bValues[0] = webSocketEndpoint;
        bValues[1] = true;
        WebSocketConnectionInfo connectionInfo = new WebSocketConnectionInfo(
                wsService, webSocketConnection, webSocketEndpoint, false);
        Callback onOpenCallback = new Callback() {
            @Override
            public void notifySuccess(Object result) {
                webSocketConnection.readNextFrame();
            }

            @Override
            public void notifyFailure(BError error) {
                error.getPrintableStackTrace();
                WebSocketUtil.closeDuringUnexpectedCondition(webSocketConnection);
                WebSocketObservabilityUtil
                        .observeError(connectionInfo, WebSocketObservabilityConstants.ERROR_TYPE_RESOURCE_INVOCATION,
                                RESOURCE_NAME_ON_CONNECT, error.getMessage());
            }
        };
        executeResource(wsService, balService, onOpenCallback, bValues, connectionInfo, RESOURCE_NAME_ON_CONNECT,
                ON_OPEN_METADATA);
    }

    public static void dispatchOnText(WebSocketConnectionInfo connectionInfo, WebSocketTextMessage textMessage,
            boolean server) {
        WebSocketObservabilityUtil.observeOnMessage(WebSocketObservabilityConstants.MESSAGE_TYPE_TEXT, connectionInfo);
        try {
            WebSocketConnection webSocketConnection = connectionInfo.getWebSocketConnection();
            WebSocketService wsService = connectionInfo.getService();
            MethodType onTextMessageResource = null;
            BObject balservice;
            if (server) {
                Object dispatchingService = wsService
                        .getWsService(connectionInfo.getWebSocketConnection().getChannelId());
                balservice = (BObject) dispatchingService;
                MethodType[] remoteFunctions = ((ServiceType) (((BValue) dispatchingService).getType()))
                        .getMethods();
                for (MethodType remoteFunc : remoteFunctions) {
                    if (remoteFunc.getName().equals(RESOURCE_NAME_ON_STRING)) {
                        onTextMessageResource = remoteFunc;
                        break;
                    }
                }
            } else {
                balservice = wsService.getBalService();
                onTextMessageResource = wsService.getResourceByName(RESOURCE_NAME_ON_STRING);
            }
            if (onTextMessageResource == null) {
                webSocketConnection.readNextFrame();
                return;
            }
            Type[] parameterTypes = onTextMessageResource.getParameterTypes();
            Object[] bValues = new Object[parameterTypes.length * 2];

            bValues[0] = connectionInfo.getWebSocketEndpoint();
            bValues[1] = true;

            boolean finalFragment = textMessage.isFinalFragment();
            Type dataType = parameterTypes[1];
            int dataTypeTag = dataType.getTag();
            WebSocketConnectionInfo.StringAggregator stringAggregator = connectionInfo
                    .createIfNullAndGetStringAggregator();
            if (finalFragment) {
                stringAggregator.appendAggregateString(textMessage.getText());
                bValues[2] = StringUtils.fromString(stringAggregator.getAggregateString());
                bValues[3] = true;
                executeResource(wsService, balservice,
                        new WebSocketResourceCallback(connectionInfo, RESOURCE_NAME_ON_STRING), bValues, connectionInfo,
                        RESOURCE_NAME_ON_STRING, ON_TEXT_METADATA);
                stringAggregator.resetAggregateString();
            } else {
                stringAggregator.appendAggregateString(textMessage.getText());
                webSocketConnection.readNextFrame();
            }
        } catch (Exception e) {
            WebSocketObservabilityUtil.observeError(connectionInfo,
                    WebSocketObservabilityConstants.ERROR_TYPE_MESSAGE_RECEIVED,
                    WebSocketObservabilityConstants.MESSAGE_TYPE_TEXT,
                    e.getMessage());
        }
    }

    private static boolean isDataBindingSupported(int dataTypeTag) {
        return dataTypeTag == TypeTags.JSON_TAG || dataTypeTag == TypeTags.RECORD_TYPE_TAG ||
                dataTypeTag == TypeTags.XML_TAG || dataTypeTag == TypeTags.ARRAY_TAG;
    }

    private static Object getAggregatedObject(WebSocketConnection webSocketConnection, Type dataType,
            String aggregateString, WebSocketConnectionInfo connectionInfo) {
        try {
            switch (dataType.getTag()) {
            case TypeTags.JSON_TAG:
                return JsonUtils.parse(aggregateString);
            case TypeTags.XML_TAG:
                BXml bxml = (BXml) XmlUtils.parse(aggregateString);
                if (bxml.getNodeType() != XmlNodeType.SEQUENCE) {
                    throw WebSocketUtil.getWebSocketError(
                            "Invalid XML data", null,
                            WebSocketConstants.ErrorCode.WsGenericError.errorCode(), null);
                }
                return bxml;
            case TypeTags.RECORD_TYPE_TAG:
                return JsonUtils.convertJSONToRecord(JsonUtils.parse(aggregateString),
                        (StructureType) dataType);
            case TypeTags.ARRAY_TAG:
                if (((ArrayType) dataType).getElementType().getTag() == TypeTags.BYTE_TAG) {
                    return ValueCreator.createArrayValue(
                            aggregateString.getBytes(StandardCharsets.UTF_8));
                }
                break;
            default:
                //Throw an exception because a different type is invalid.
                //Cannot reach here because of compiler plugin validation.
                throw WebSocketUtil.getWebSocketError(
                        "Invalid resource signature.", null,
                        WebSocketConstants.ErrorCode.WsGenericError.errorCode(), null);
            }
        } catch (WebSocketException ex) {
            webSocketConnection.terminateConnection(1003, ex.detailMessage());
            WebSocketObservabilityUtil.observeError(connectionInfo,
                    WebSocketObservabilityConstants.ERROR_TYPE_MESSAGE_RECEIVED,
                    WebSocketObservabilityConstants.MESSAGE_TYPE_TEXT,
                    ex.getMessage());
        } catch (Exception ex) {
            String errorMessage = WebSocketUtil.getErrorMessage(ex);
            if (errorMessage.length() > 123) {
                errorMessage = errorMessage.substring(0, 120) + "...";
            }
            webSocketConnection.terminateConnection(1003, errorMessage);
            log.error("Data binding failed. Hence connection terminated. ", ex);
            WebSocketObservabilityUtil.observeError(connectionInfo,
                    WebSocketObservabilityConstants.ERROR_TYPE_MESSAGE_RECEIVED,
                    WebSocketObservabilityConstants.MESSAGE_TYPE_TEXT,
                    ex.getMessage());
        }
        return null;
    }

    public static void dispatchOnBinary(WebSocketConnectionInfo connectionInfo, WebSocketBinaryMessage binaryMessage,
            boolean server) {
        WebSocketObservabilityUtil.observeOnMessage(WebSocketObservabilityConstants.MESSAGE_TYPE_BINARY,
                connectionInfo);
        try {
            WebSocketConnection webSocketConnection = connectionInfo.getWebSocketConnection();
            WebSocketService wsService = connectionInfo.getService();
            MethodType onBinaryMessageResource = null;
            BObject balservice;
            if (server) {
                Object dispatchingService = wsService
                        .getWsService(connectionInfo.getWebSocketConnection().getChannelId());
                balservice = (BObject) dispatchingService;
                MethodType[] remoteFunctions = ((ServiceType) (((BValue) dispatchingService).getType()))
                        .getMethods();
                for (MethodType remoteFunc : remoteFunctions) {
                    if (remoteFunc.getName().equals(RESOURCE_NAME_ON_BINARY)) {
                        onBinaryMessageResource = remoteFunc;
                        break;
                    }
                }
            } else {
                balservice = wsService.getBalService();
                onBinaryMessageResource = wsService.getResourceByName(RESOURCE_NAME_ON_BINARY);
            }
            if (onBinaryMessageResource == null) {
                webSocketConnection.readNextFrame();
                return;
            }
            boolean finalFragment = binaryMessage.isFinalFragment();
            Type[] paramDetails = onBinaryMessageResource.getParameterTypes();
            Object[] bValues = new Object[paramDetails.length * 2];
            WebSocketConnectionInfo.ByteArrAggregator byteAggregator = connectionInfo
                    .createIfNullAndGetByteArrAggregator();
            if (finalFragment) {
                byteAggregator.appendAggregateArr(binaryMessage.getByteArray());
                bValues[0] = connectionInfo.getWebSocketEndpoint();
                bValues[1] = true;
                bValues[2] = ValueCreator.createArrayValue(byteAggregator.getAggregateByteArr());
                bValues[3] = true;
                executeResource(wsService, balservice, new WebSocketResourceCallback(
                                connectionInfo, RESOURCE_NAME_ON_BINARY), bValues, connectionInfo,
                        RESOURCE_NAME_ON_BINARY, ON_BINARY_METADATA);
                byteAggregator.resetAggregateByteArr();
            } else {
                byteAggregator.appendAggregateArr(binaryMessage.getByteArray());
                webSocketConnection.readNextFrame();
            }
        } catch (Exception e) {
            WebSocketObservabilityUtil.observeError(connectionInfo,
                    WebSocketObservabilityConstants.ERROR_TYPE_MESSAGE_RECEIVED,
                    WebSocketObservabilityConstants.MESSAGE_TYPE_BINARY,
                    e.getMessage());
        }
    }

    public static void dispatchOnPingOnPong(WebSocketConnectionInfo connectionInfo,
            WebSocketControlMessage controlMessage, boolean server) {
        if (controlMessage.getControlSignal() == WebSocketControlSignal.PING) {
            WebSocketResourceDispatcher.dispatchOnPing(connectionInfo, controlMessage, server);
        } else if (controlMessage.getControlSignal() == WebSocketControlSignal.PONG) {
            WebSocketResourceDispatcher.dispatchOnPong(connectionInfo, controlMessage, server);
        }
    }

    private static void dispatchOnPing(WebSocketConnectionInfo connectionInfo, WebSocketControlMessage controlMessage,
            boolean server) {
        WebSocketObservabilityUtil.observeOnMessage(WebSocketObservabilityConstants.MESSAGE_TYPE_PING,
                connectionInfo);
        try {
            WebSocketService wsService = connectionInfo.getService();
            MethodType onPingMessageResource = null;
            BObject balservice = null;
            if (server) {
                Object dispatchingService = wsService
                        .getWsService(connectionInfo.getWebSocketConnection().getChannelId());
                balservice = (BObject) dispatchingService;
                MethodType[] remoteFunctions = ((ServiceType) (((BValue) dispatchingService).getType()))
                        .getMethods();
                for (MethodType remoteFunc : remoteFunctions) {
                    if (remoteFunc.getName().equals(RESOURCE_NAME_ON_PING)) {
                        onPingMessageResource = remoteFunc;
                        break;
                    }
                }
            } else {
                balservice = wsService.getBalService();
                onPingMessageResource = wsService.getResourceByName(RESOURCE_NAME_ON_PING);
            }
            if (onPingMessageResource == null) {
                pongAutomatically(controlMessage);
                return;
            }
            Type[] paramTypes = onPingMessageResource.getParameterTypes();
            Object[] bValues = new Object[paramTypes.length * 2];
            bValues[0] = connectionInfo.getWebSocketEndpoint();
            bValues[1] = true;
            bValues[2] = ValueCreator.createArrayValue(controlMessage.getByteArray());
            bValues[3] = true;
            executeResource(wsService, balservice, new WebSocketResourceCallback(
                            connectionInfo, WebSocketConstants.RESOURCE_NAME_ON_PING),
                    bValues, connectionInfo, WebSocketConstants.RESOURCE_NAME_ON_PING, ON_PING_METADATA);
        } catch (Exception e) {
            //Observe error
            WebSocketObservabilityUtil.observeError(connectionInfo,
                    WebSocketObservabilityConstants.ERROR_TYPE_MESSAGE_RECEIVED,
                    WebSocketObservabilityConstants.MESSAGE_TYPE_PING,
                    e.getMessage());
        }
    }

    private static void dispatchOnPong(WebSocketConnectionInfo connectionInfo, WebSocketControlMessage controlMessage,
            boolean server) {
        WebSocketObservabilityUtil.observeOnMessage(WebSocketObservabilityConstants.MESSAGE_TYPE_PONG,
                connectionInfo);
        try {
            WebSocketConnection webSocketConnection = connectionInfo.getWebSocketConnection();
            WebSocketService wsService = connectionInfo.getService();
            MethodType onPongMessageResource = null;
            BObject balservice = null;
            if (server) {
                Object dispatchingService = wsService
                        .getWsService(connectionInfo.getWebSocketConnection().getChannelId());
                balservice = (BObject) dispatchingService;
                MethodType[] remoteFunctions = ((ServiceType) (((BValue) dispatchingService)
                        .getType())).getMethods();
                for (MethodType remoteFunc : remoteFunctions) {
                    if (remoteFunc.getName().equals(RESOURCE_NAME_ON_PONG)) {
                        onPongMessageResource = remoteFunc;
                        break;
                    }
                }
            } else {
                balservice = wsService.getBalService();
                onPongMessageResource = wsService.getResourceByName(RESOURCE_NAME_ON_PONG);
            }
            if (onPongMessageResource == null) {
                webSocketConnection.readNextFrame();
                return;
            }
            Type[] paramDetails = onPongMessageResource.getParameterTypes();
            Object[] bValues = new Object[paramDetails.length * 2];
            bValues[0] = connectionInfo.getWebSocketEndpoint();
            bValues[1] = true;
            bValues[2] = ValueCreator.createArrayValue(controlMessage.getByteArray());
            bValues[3] = true;
            executeResource(wsService, balservice, new WebSocketResourceCallback(
                            connectionInfo, RESOURCE_NAME_ON_PONG),
                    bValues, connectionInfo, RESOURCE_NAME_ON_PONG, ON_PONG_METADATA);
        } catch (Exception e) {
            WebSocketObservabilityUtil.observeError(connectionInfo,
                    WebSocketObservabilityConstants.ERROR_TYPE_MESSAGE_RECEIVED,
                    WebSocketObservabilityConstants.MESSAGE_TYPE_PONG,
                    e.getMessage());
        }
    }

    public static void dispatchOnClose(WebSocketConnectionInfo connectionInfo, WebSocketCloseMessage closeMessage,
            boolean server) {
        WebSocketObservabilityUtil.observeOnMessage(WebSocketObservabilityConstants.MESSAGE_TYPE_CLOSE,
                connectionInfo);
        try {
            WebSocketUtil.setListenerOpenField(connectionInfo);
            WebSocketConnection webSocketConnection = connectionInfo.getWebSocketConnection();
            WebSocketService wsService = connectionInfo.getService();
            MethodType onCloseResource = null;
            int closeCode = closeMessage.getCloseCode();
            String closeReason = closeMessage.getCloseReason();
            BObject balservice = null;
            if (server) {
                Object dispatchingService = wsService
                        .getWsService(connectionInfo.getWebSocketConnection().getChannelId());
                balservice = (BObject) dispatchingService;
                MethodType[] remoteFunctions = ((ServiceType) (((BValue) dispatchingService)
                        .getType())).getMethods();
                for (MethodType remoteFunc : remoteFunctions) {
                    if (remoteFunc.getName().equals(RESOURCE_NAME_ON_CLOSE)) {
                        onCloseResource = remoteFunc;
                        break;
                    }
                }
            } else {
                balservice = wsService.getBalService();
                onCloseResource = wsService.getResourceByName(RESOURCE_NAME_ON_CLOSE);
            }
            if (onCloseResource == null) {
                finishConnectionClosureIfOpen(webSocketConnection, closeCode, connectionInfo);
                return;
            }

            Type[] paramDetails = onCloseResource.getParameterTypes();
            Object[] bValues = new Object[paramDetails.length * 2];
            bValues[0] = connectionInfo.getWebSocketEndpoint();
            bValues[1] = true;
            bValues[2] = closeCode;
            bValues[3] = true;
            bValues[4] = closeReason == null ? StringUtils.fromString("") : StringUtils.fromString(closeReason);
            bValues[5] = true;
            Callback onCloseCallback = new Callback() {
                @Override
                public void notifySuccess(Object result) {
                    finishConnectionClosureIfOpen(webSocketConnection, closeCode, connectionInfo);
                }

                @Override
                public void notifyFailure(BError error) {
                    error.printStackTrace();
                    finishConnectionClosureIfOpen(webSocketConnection, closeCode, connectionInfo);
                    //Observe error
                    WebSocketObservabilityUtil.observeError(
                            connectionInfo, WebSocketObservabilityConstants.ERROR_TYPE_RESOURCE_INVOCATION,
                            WebSocketConstants.RESOURCE_NAME_ON_CLOSE,
                            error.getMessage());
                }
            };
            executeResource(wsService, balservice, onCloseCallback,
                    bValues, connectionInfo, WebSocketConstants.RESOURCE_NAME_ON_CLOSE, ON_CLOSE_METADATA);
        } catch (Exception e) {
            WebSocketObservabilityUtil.observeError(connectionInfo,
                    WebSocketObservabilityConstants.ERROR_TYPE_MESSAGE_RECEIVED,
                    WebSocketObservabilityConstants.MESSAGE_TYPE_CLOSE,
                    e.getMessage());
        }
    }

    private static void finishConnectionClosureIfOpen(WebSocketConnection webSocketConnection, int closeCode,
            WebSocketConnectionInfo connectionInfo) {
        if (webSocketConnection.isOpen()) {
            ChannelFuture finishFuture;
            if (closeCode == WebSocketConstants.STATUS_CODE_FOR_NO_STATUS_CODE_PRESENT) {
                finishFuture = webSocketConnection.finishConnectionClosure();
            } else {
                finishFuture = webSocketConnection.finishConnectionClosure(closeCode, null);
            }
            finishFuture.addListener(closeFuture -> WebSocketUtil.setListenerOpenField(connectionInfo));
        }
    }

    public static void dispatchOnError(WebSocketConnectionInfo connectionInfo, Throwable throwable, boolean server) {
        try {
            WebSocketUtil.setListenerOpenField(connectionInfo);
        } catch (IllegalAccessException e) {
            connectionInfo.getWebSocketEndpoint().set(WebSocketConstants.LISTENER_IS_OPEN_FIELD, false);
        }
        WebSocketService webSocketService = connectionInfo.getService();
        MethodType onErrorResource = null;
        if (isUnexpectedError(throwable)) {
            log.error("Unexpected error", throwable);
            WebSocketObservabilityUtil.observeError(connectionInfo,
                    WebSocketObservabilityConstants.ERROR_TYPE_MESSAGE_RECEIVED,
                    WebSocketObservabilityConstants.MESSAGE_TYPE_TEXT,
                    "Unexpected error");
        }
        BObject balservice = null;
        if (server) {
            Object dispatchingService = null;
            try {
                dispatchingService = webSocketService
                        .getWsService(connectionInfo.getWebSocketConnection().getChannelId());
            } catch (IllegalAccessException ex) {
                connectionInfo.getWebSocketEndpoint().set(WebSocketConstants.LISTENER_IS_OPEN_FIELD, false);
            }
            balservice = (BObject) dispatchingService;
            MethodType[] remoteFunctions = ((ServiceType) (((BValue) dispatchingService)
                    .getType())).getMethods();
            for (MethodType remoteFunc : remoteFunctions) {
                if (remoteFunc.getName().equals(RESOURCE_NAME_ON_ERROR)) {
                    onErrorResource = remoteFunc;
                    break;
                }
            }
        } else {
            balservice = webSocketService.getBalService();
            onErrorResource = webSocketService.getResourceByName(RESOURCE_NAME_ON_ERROR);
        }
        if (onErrorResource == null) {
            ErrorCreator.createError(throwable.getCause()).printStackTrace();
            return;
        }
        Object[] bValues = new Object[onErrorResource.getParameterTypes().length * 2];
        bValues[0] = connectionInfo.getWebSocketEndpoint();
        bValues[1] = true;
        bValues[2] = WebSocketUtil.createErrorByType(throwable);
        bValues[3] = true;
        Callback onErrorCallback = new Callback() {
            @Override
            public void notifySuccess(Object result) {
                // Do nothing.
            }

            @Override
            public void notifyFailure(BError error) {
                error.printStackTrace();
                WebSocketObservabilityUtil.observeError(
                        connectionInfo, WebSocketObservabilityConstants.ERROR_TYPE_RESOURCE_INVOCATION,
                        RESOURCE_NAME_ON_ERROR,
                        error.getMessage());
            }
        };
        executeResource(webSocketService, balservice, onErrorCallback, bValues, connectionInfo, RESOURCE_NAME_ON_ERROR,
                ON_ERROR_METADATA);
    }

    private static boolean isUnexpectedError(Throwable throwable) {
        return !(throwable instanceof CorruptedFrameException);
    }

    public static void dispatchOnIdleTimeout(WebSocketConnectionInfo connectionInfo, boolean server) {
        try {
            WebSocketConnection webSocketConnection = connectionInfo.getWebSocketConnection();
            WebSocketService wsService = connectionInfo.getService();
            MethodType onIdleTimeoutResource = null;
            BObject balservice = null;
            if (server) {
                Object dispatchingService = wsService
                        .getWsService(connectionInfo.getWebSocketConnection().getChannelId());
                balservice = (BObject) dispatchingService;
                MethodType[] remoteFunctions = ((ServiceType) (((BValue) dispatchingService)
                        .getType())).getMethods();
                for (MethodType remoteFunc : remoteFunctions) {
                    if (remoteFunc.getName().equals(RESOURCE_NAME_ON_IDLE_TIMEOUT)) {
                        onIdleTimeoutResource = remoteFunc;
                        break;
                    }
                }
            } else {
                balservice = wsService.getBalService();
                onIdleTimeoutResource = wsService.getResourceByName(RESOURCE_NAME_ON_IDLE_TIMEOUT);
            }
            if (onIdleTimeoutResource == null) {
                return;
            }
            Type[] paramDetails = onIdleTimeoutResource.getParameterTypes();
            Object[] bValues = new Object[paramDetails.length * 2];
            bValues[0] = connectionInfo.getWebSocketEndpoint();
            bValues[1] = true;

            Callback onIdleTimeoutCallback = new Callback() {
                @Override
                public void notifySuccess(Object result) {
                    // Do nothing.
                }

                @Override
                public void notifyFailure(BError error) {
                    error.printStackTrace();
                    WebSocketUtil.closeDuringUnexpectedCondition(webSocketConnection);
                }
            };
            executeResource(wsService, balservice, onIdleTimeoutCallback, bValues, connectionInfo,
                    RESOURCE_NAME_ON_IDLE_TIMEOUT, ON_TIMEOUT_METADATA);
        } catch (Exception e) {
            log.error("Error on idle timeout", e);
            WebSocketObservabilityUtil.observeError(connectionInfo,
                    WebSocketObservabilityConstants.ERROR_TYPE_MESSAGE_RECEIVED,
                    WebSocketObservabilityConstants.MESSAGE_TYPE_TEXT,
                    e.getMessage());
        }
    }

    private static void pongAutomatically(WebSocketControlMessage controlMessage) {
        WebSocketConnection webSocketConnection = controlMessage.getWebSocketConnection();
        webSocketConnection.pong(controlMessage.getByteBuffer()).addListener(future -> {
            Throwable cause = future.cause();
            if (!future.isSuccess() && cause != null) {
                ErrorCreator.createError(cause).printStackTrace();
            }
            webSocketConnection.readNextFrame();
        });
    }

    private static void executeResource(WebSocketService wsService, BObject balservice, Callback callback,
            Object[] bValues, WebSocketConnectionInfo connectionInfo, String resource, StrandMetadata metaData) {
        if (ObserveUtils.isTracingEnabled()) {
            Map<String, Object> properties = new HashMap<>();
            WebSocketObserverContext observerContext = new WebSocketObserverContext(connectionInfo);
            properties.put(ObservabilityConstants.KEY_OBSERVER_CONTEXT, observerContext);
            wsService.getRuntime().invokeMethodAsync(balservice, resource, null, metaData, callback,
                    properties, bValues);
        } else {
            wsService.getRuntime().invokeMethodAsync(balservice, resource, null, metaData, callback,
                    bValues);
        }
        WebSocketObservabilityUtil.observeResourceInvocation(connectionInfo, resource);
    }

    private static void enrichHttpCallerWithConnectionInfo(BObject httpCaller, HttpCarbonMessage inboundMsg,
            BMap config) {
        BMap<BString, Object> remote = ValueCreatorUtils.createHTTPRecordValue("Remote");
        BMap<BString, Object> local = ValueCreatorUtils.createHTTPRecordValue("Local");
        Object remoteSocketAddress = inboundMsg.getProperty("REMOTE_ADDRESS");
        if (remoteSocketAddress instanceof InetSocketAddress) {
            InetSocketAddress inetSocketAddress = (InetSocketAddress) remoteSocketAddress;
            BString remoteHost = StringUtils.fromString(inetSocketAddress.getHostString());
            long remotePort = (long) inetSocketAddress.getPort();
            remote.put(HttpConstants.REMOTE_HOST_FIELD, remoteHost);
            remote.put(HttpConstants.REMOTE_PORT_FIELD, remotePort);
        }

        httpCaller.set(HttpConstants.REMOTE_STRUCT_FIELD, remote);
        Object localSocketAddress = inboundMsg.getProperty("LOCAL_ADDRESS");
        if (localSocketAddress instanceof InetSocketAddress) {
            InetSocketAddress inetSocketAddress = (InetSocketAddress) localSocketAddress;
            String localHost = inetSocketAddress.getHostName();
            long localPort = (long) inetSocketAddress.getPort();
            local.put(HttpConstants.LOCAL_HOST_FIELD, StringUtils.fromString(localHost));
            local.put(HttpConstants.LOCAL_PORT_FIELD, localPort);
        }

        httpCaller.set(HttpConstants.LOCAL_STRUCT_INDEX, local);
        httpCaller.set(HttpConstants.SERVICE_ENDPOINT_PROTOCOL_FIELD,
                StringUtils.fromString((String) inboundMsg.getProperty("PROTOCOL")));
        // TODO: can't add the following as it looks for an http:Listener config. Check this.
        // check if we can use the http module's function.
        //        httpCaller.set(HttpConstants.SERVICE_ENDPOINT_CONFIG_FIELD, config);
        httpCaller.addNativeData("remoteSocketAddress", remoteSocketAddress);
    }
}
