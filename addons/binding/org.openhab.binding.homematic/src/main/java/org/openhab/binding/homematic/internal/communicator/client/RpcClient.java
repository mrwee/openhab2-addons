/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.homematic.internal.communicator.client;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.openhab.binding.homematic.internal.common.HomematicConfig;
import org.openhab.binding.homematic.internal.communicator.message.RpcRequest;
import org.openhab.binding.homematic.internal.communicator.parser.GetAllScriptsParser;
import org.openhab.binding.homematic.internal.communicator.parser.GetAllSystemVariablesParser;
import org.openhab.binding.homematic.internal.communicator.parser.GetDeviceDescriptionParser;
import org.openhab.binding.homematic.internal.communicator.parser.GetParamsetDescriptionParser;
import org.openhab.binding.homematic.internal.communicator.parser.GetParamsetParser;
import org.openhab.binding.homematic.internal.communicator.parser.GetValueParser;
import org.openhab.binding.homematic.internal.communicator.parser.HomegearLoadDeviceNamesParser;
import org.openhab.binding.homematic.internal.communicator.parser.ListBidcosInterfacesParser;
import org.openhab.binding.homematic.internal.communicator.parser.ListDevicesParser;
import org.openhab.binding.homematic.internal.communicator.parser.RssiInfoParser;
import org.openhab.binding.homematic.internal.model.HmChannel;
import org.openhab.binding.homematic.internal.model.HmDatapoint;
import org.openhab.binding.homematic.internal.model.HmDevice;
import org.openhab.binding.homematic.internal.model.HmGatewayInfo;
import org.openhab.binding.homematic.internal.model.HmInterface;
import org.openhab.binding.homematic.internal.model.HmParamsetType;
import org.openhab.binding.homematic.internal.model.HmRssiInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client implementation for sending messages via BIN-RPC to a Homematic gateway.
 *
 * @author Gerhard Riegler - Initial contribution
 */
public abstract class RpcClient {
    private static final Logger logger = LoggerFactory.getLogger(RpcClient.class);
    protected static final int MAX_RPC_RETRY = 1;

    protected HomematicConfig config;

    public RpcClient(HomematicConfig config) {
        this.config = config;
    }

    /**
     * Disposes the client.
     */
    public abstract void dispose();

    /**
     * Returns a RpcRequest for this client.
     */
    protected abstract RpcRequest createRpcRequest(String methodName);

    /**
     * Returns the callback url for this client.
     */
    protected abstract String getRpcCallbackUrl();

    /**
     * Sends the RPC message to the gateway.
     */
    protected abstract Object[] sendMessage(int port, RpcRequest request) throws IOException;

    /**
     * Register a callback for the specified interface where the Homematic gateway can send its events.
     */
    public void init(HmInterface hmInterface, String clientId) throws IOException {
        RpcRequest request = createRpcRequest("init");
        request.addArg(getRpcCallbackUrl());
        request.addArg(clientId);
        sendMessage(config.getRpcPort(hmInterface), request);
    }

    /**
     * Release a callback for the specified interface.
     */
    public void release(HmInterface hmInterface) throws IOException {
        RpcRequest request = createRpcRequest("init");
        request.addArg(getRpcCallbackUrl());
        sendMessage(config.getRpcPort(hmInterface), request);
    }

    /**
     * Returns all variable metadata and values from a Homegear gateway.
     */
    public void getAllSystemVariables(HmChannel channel) throws IOException {
        RpcRequest request = createRpcRequest("getAllSystemVariables");
        new GetAllSystemVariablesParser(channel).parse(sendMessage(config.getRpcPort(channel), request));
    }

    /**
     * Loads all device names from a Homegear gateway.
     */
    public void loadDeviceNames(HmInterface hmInterface, Collection<HmDevice> devices) throws IOException {
        RpcRequest request = createRpcRequest("getDeviceInfo");
        new HomegearLoadDeviceNamesParser(devices).parse(sendMessage(config.getRpcPort(hmInterface), request));
    }

    /**
     * Returns true, if the interface is available on the gateway.
     */
    public void checkInterface(HmInterface hmInterface) throws IOException {
        RpcRequest request = createRpcRequest("init");
        request.addArg("http://openhab.validation:1000");
        sendMessage(config.getRpcPort(hmInterface), request);
    }

    /**
     * Validates the connection to the interface by calling the listBidcosInterfaces method.
     */
    public void validateConnection(HmInterface hmInterface) throws IOException {
        RpcRequest request = createRpcRequest("listBidcosInterfaces");
        sendMessage(config.getRpcPort(hmInterface), request);
    }

    /**
     * Returns all script metadata from a Homegear gateway.
     */
    public void getAllScripts(HmChannel channel) throws IOException {
        RpcRequest request = createRpcRequest("getAllScripts");
        new GetAllScriptsParser(channel).parse(sendMessage(config.getRpcPort(channel), request));
    }

    /**
     * Returns all device and channel metadata.
     */
    public Collection<HmDevice> listDevices(HmInterface hmInterface) throws IOException {
        RpcRequest request = createRpcRequest("listDevices");
        return new ListDevicesParser(hmInterface, config).parse(sendMessage(config.getRpcPort(hmInterface), request));
    }

    /**
     * Loads all datapoint metadata into the given channel.
     */
    public void addChannelDatapoints(HmChannel channel, HmParamsetType paramsetType) throws IOException {
        RpcRequest request = createRpcRequest("getParamsetDescription");
        request.addArg(getRpcAddress(channel.getDevice().getAddress()) + ":" + channel.getNumber());
        request.addArg(paramsetType.toString());
        try {
            new GetParamsetDescriptionParser(channel, paramsetType)
                    .parse(sendMessage(config.getRpcPort(channel), request));
        } catch (UnknownParameterSetException ex) {
            // ignore MASTER paramset
            if (paramsetType == HmParamsetType.VALUES) {
                throw ex;
            }
        }
    }

    /**
     * Sets all datapoint values for the given channel.
     */
    public void setChannelDatapointValues(HmChannel channel, HmParamsetType paramsetType) throws IOException {
        RpcRequest request = createRpcRequest("getParamset");
        request.addArg(getRpcAddress(channel.getDevice().getAddress()) + ":" + channel.getNumber());
        request.addArg(paramsetType.toString());
        if (channel.getDevice().getHmInterface() == HmInterface.CUXD && paramsetType == HmParamsetType.VALUES) {
            setChannelDatapointValues(channel);
        } else {
            try {
                new GetParamsetParser(channel, paramsetType).parse(sendMessage(config.getRpcPort(channel), request));
            } catch (UnknownRpcFailureException ex) {
                if (paramsetType == HmParamsetType.VALUES) {
                    logger.debug(
                            "RpcResponse unknown RPC failure (-1 Failure), fetching values with another API method for device '{}'",
                            channel.getDevice().getAddress());
                    setChannelDatapointValues(channel);
                } else {
                    throw ex;
                }
            } catch (UnknownParameterSetException ex) {
                // ignore MASTER paramset
                if (paramsetType == HmParamsetType.VALUES) {
                    throw ex;
                }
            }
        }
    }

    /**
     * Reads all VALUES datapoints individually, fallback method if setChannelDatapointValues throws a -1 Failure
     * exception.
     */
    private void setChannelDatapointValues(HmChannel channel) throws IOException {
        for (HmDatapoint dp : channel.getDatapoints().values()) {
            if (dp.isReadable() && !dp.isVirtual() && dp.getParamsetType() == HmParamsetType.VALUES) {
                RpcRequest request = createRpcRequest("getValue");
                request.addArg(getRpcAddress(channel.getDevice().getAddress()) + ":" + channel.getNumber());
                request.addArg(dp.getName());
                new GetValueParser(dp).parse(sendMessage(config.getRpcPort(channel), request));
            }
        }
    }

    /**
     * Tries to identify the gateway and returns the GatewayInfo.
     */
    public HmGatewayInfo getGatewayInfo(String id) throws IOException {
        RpcRequest request = createRpcRequest("getDeviceDescription");
        request.addArg("BidCoS-RF");
        GetDeviceDescriptionParser ddParser = new GetDeviceDescriptionParser();
        ddParser.parse(sendMessage(config.getRpcPort(HmInterface.RF), request));

        boolean isHomegear = StringUtils.equalsIgnoreCase(ddParser.getType(), "Homegear");

        request = createRpcRequest("listBidcosInterfaces");
        ListBidcosInterfacesParser biParser = new ListBidcosInterfacesParser(ddParser.getDeviceInterface(), isHomegear);
        biParser.parse(sendMessage(config.getRpcPort(HmInterface.RF), request));

        HmGatewayInfo gatewayInfo = new HmGatewayInfo();
        gatewayInfo.setAddress(biParser.getGatewayAddress());
        if (isHomegear) {
            gatewayInfo.setId(HmGatewayInfo.ID_HOMEGEAR);
            gatewayInfo.setType(ddParser.getType());
            gatewayInfo.setFirmware(ddParser.getFirmware());
        } else if (StringUtils.startsWithIgnoreCase(biParser.getType(), "CCU")
                || config.getGatewayType().equalsIgnoreCase(HomematicConfig.GATEWAY_TYPE_CCU)) {
            gatewayInfo.setId(HmGatewayInfo.ID_CCU);
            String type = StringUtils.isBlank(biParser.getType()) ? "CCU" : biParser.getType();
            gatewayInfo.setType(type);
            gatewayInfo.setFirmware(ddParser.getFirmware());
        } else {
            gatewayInfo.setId(HmGatewayInfo.ID_DEFAULT);
            gatewayInfo.setType(biParser.getType());
            gatewayInfo.setFirmware(biParser.getFirmware());
        }

        if (gatewayInfo.isCCU() || config.hasWiredPort()) {
            gatewayInfo.setWiredInterface(hasInterface(HmInterface.WIRED, id));
        }

        if (gatewayInfo.isCCU() || config.hasHmIpPort()) {
            gatewayInfo.setHmipInterface(hasInterface(HmInterface.HMIP, id));
        }

        if (gatewayInfo.isCCU() || config.hasCuxdPort()) {
            gatewayInfo.setCuxdInterface(hasInterface(HmInterface.CUXD, id));
        }

        if (gatewayInfo.isCCU() || config.hasGroupPort()) {
            gatewayInfo.setGroupInterface(hasInterface(HmInterface.GROUP, id));
        }

        return gatewayInfo;
    }

    /**
     * Returns true, if a connection is possible with the given interface.
     */
    private boolean hasInterface(HmInterface hmInterface, String id) throws IOException {
        try {
            checkInterface(hmInterface);
            return true;
        } catch (IOException ex) {
            logger.info("Interface '{}' on gateway '{}' not available, disabling support", hmInterface, id);
            return false;
        }
    }

    /**
     * Sets the value of the datapoint.
     */
    public void setDatapointValue(HmDatapoint dp, Object value) throws IOException {
        if (dp.isIntegerType() && value instanceof Double) {
            value = ((Number) value).intValue();
        }

        RpcRequest request;
        if (HmParamsetType.VALUES == dp.getParamsetType()) {
            request = createRpcRequest("setValue");
            request.addArg(getRpcAddress(dp.getChannel().getDevice().getAddress()) + ":" + dp.getChannel().getNumber());
            request.addArg(dp.getName());
            request.addArg(value);
        } else {
            request = createRpcRequest("putParamset");
            request.addArg(getRpcAddress(dp.getChannel().getDevice().getAddress()) + ":" + dp.getChannel().getNumber());
            request.addArg(HmParamsetType.MASTER.toString());
            Map<String, Object> paramSet = new HashMap<String, Object>();
            paramSet.put(dp.getName(), value);
            request.addArg(paramSet);
        }
        sendMessage(config.getRpcPort(dp.getChannel()), request);
    }

    /**
     * Sets the value of a system variable on a Homegear gateway.
     */
    public void setSystemVariable(HmDatapoint dp, Object value) throws IOException {
        RpcRequest request = createRpcRequest("setSystemVariable");
        request.addArg(dp.getInfo());
        request.addArg(value);
        sendMessage(config.getRpcPort(dp.getChannel()), request);
    }

    /**
     * Executes a script on the Homegear gateway.
     */
    public void executeScript(HmDatapoint dp) throws IOException {
        RpcRequest request = createRpcRequest("runScript");
        request.addArg(dp.getInfo());
        sendMessage(config.getRpcPort(dp.getChannel()), request);
    }

    /**
     * Enables/disables the install mode for given seconds.
     */
    public void setInstallMode(HmInterface hmInterface, boolean enable, int seconds) throws IOException {
        RpcRequest request = createRpcRequest("setInstallMode");
        request.addArg(enable);
        request.addArg(seconds);
        request.addArg(1);
        sendMessage(config.getRpcPort(hmInterface), request);
    }

    /**
     * Deletes the device from the gateway.
     */
    public void deleteDevice(HmDevice device, int flags) throws IOException {
        RpcRequest request = createRpcRequest("deleteDevice");
        request.addArg(device.getAddress());
        request.addArg(flags);
        sendMessage(config.getRpcPort(device.getHmInterface()), request);
    }

    /**
     * Returns the rpc address from a device address, correctly handling groups.
     */
    private String getRpcAddress(String address) {
        if (address != null && address.startsWith("T-")) {
            address = "*" + address.substring(2);
        }
        return address;
    }

    /**
     * Returns the rssi values for all devices.
     */
    public List<HmRssiInfo> loadRssiInfo(HmInterface hmInterface) throws IOException {
        RpcRequest request = createRpcRequest("rssiInfo");
        return new RssiInfoParser(config).parse(sendMessage(config.getRpcPort(hmInterface), request));
    }

}
