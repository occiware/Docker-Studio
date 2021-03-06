/**
 * Copyright (c) 2016-2017 Inria
 *  
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 * - Christophe Gourdin <christophe.gourdin@inria.fr>
 *  
 */
package org.eclipse.cmf.occi.docker.connector;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.eclipse.cmf.occi.core.Resource;
import org.eclipse.cmf.occi.docker.Container;
import org.eclipse.cmf.occi.docker.Machine;
import org.eclipse.cmf.occi.docker.Network;
import org.eclipse.cmf.occi.docker.Networklink;
import org.eclipse.cmf.occi.docker.Volumesfrom;
import org.eclipse.cmf.occi.docker.connector.exceptions.DockerException;
import org.eclipse.cmf.occi.docker.connector.helpers.DockerConfigurationHelper;
import org.eclipse.cmf.occi.docker.connector.helpers.DockerMachineHelper;
import org.eclipse.cmf.occi.docker.connector.observer.StatsCallBack;
import org.eclipse.cmf.occi.docker.connector.utils.EventCallBack;
import org.eclipse.cmf.occi.docker.connector.utils.ModelHandler;
import org.eclipse.cmf.occi.infrastructure.Compute;
import org.eclipse.cmf.occi.infrastructure.ComputeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateNetworkCmd;
import com.github.dockerjava.api.command.CreateNetworkResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Link;
import com.github.dockerjava.api.model.LxcConf;
import com.github.dockerjava.api.model.Network.Ipam.Config;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports.Binding;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.api.model.VolumesFrom;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import com.google.common.collect.Multimap;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

/**
 * Manage the docker client and used by connector when executing actions.
 * 
 * @author Christophe Gourdin
 *
 */
public class DockerClientManager {

	private DockerClient dockerClient = null;

	private Compute compute = null;

	private Map<String, List<String>> images = new HashMap<>();

	private static Logger LOGGER = LoggerFactory.getLogger(DockerClientManager.class);

	// private PreferenceValues properties = new PreferenceValues();

	public static final String DEFAULT_IMAGE_NAME = "busybox";

	public DockerClientManager(Compute compute) throws DockerException {
		this.compute = compute;
		// Build a docker client related to this compute, if compute is null,
		// dockerclient will be relative to this local machine.
		this.dockerClient = DockerConfigurationHelper.buildDockerClient(compute);
	}

	public DockerClientManager(Compute compute, EventCallBack event) throws DockerException {
		this.compute = compute;
		this.dockerClient = DockerConfigurationHelper.buildDockerClient(compute);
		this.dockerClient.eventsCmd().exec(event);
	}

	public DockerClientManager() {

	}

	public DockerClient getDockerClient() {
		return dockerClient;
	}

	public void setDockerClient(DockerClient dockerClient) {
		this.dockerClient = dockerClient;
	}

	public Compute getCompute() {
		return compute;
	}

	public void setCompute(Compute compute) {
		this.compute = compute;
	}

	public Map<String, List<String>> getImages() {
		return images;
	}

	public void setImages(Map<String, List<String>> images) {
		this.images = images;
	}

	// Action methods part.

	/**
	 * Is the container exist on real infra and exist in compute machine.
	 * 
	 * @param compute
	 * @param containerId
	 *            to check
	 * @return true if the container exist on infastructure and exist on compute.
	 * @throws DockerException
	 */
	public boolean containerIsInsideMachine(Compute compute, final String containerId) throws DockerException {

		// Check if it exist on compute.
		InspectContainerResponse containerResponse = inspectContainer(compute, containerId);
		String name = containerResponse.getName().replaceAll("/", "");

		// On model level...
		List<Container> listContainer = DockerMachineHelper.listContainerModels(compute);

		for (Container ec : listContainer) {
			if (ec.getName().equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 
	 * @param compute
	 * @param container
	 * @return
	 * @throws DockerException
	 */
	public boolean containerIsInsideMachine(Compute compute, final Container container) throws DockerException {

		// Check if it exist on compute.
		InspectContainerResponse containerResponse = inspectContainer(compute, container);
		if (containerResponse == null) {
			// Not found on real virtal machine.
			return false;
		}
		String name = containerResponse.getName().replaceAll("/", "");

		// On model level...
		List<Container> listContainer = DockerMachineHelper.listContainerModels(compute);

		for (Container ec : listContainer) {
			if (ec.getName().equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 
	 * @param compute
	 * @param container
	 * @return A response docker-java object CreateContainerResponse.
	 * @throws DockerException
	 */
	public CreateContainerResponse createContainer(Compute computeMachine, Container container) throws DockerException {

		preCheckDockerClient(computeMachine);

		CreateContainerCmd createContainer = containerBuilder(container, null);

		CreateContainerResponse createContainerResponse = createContainer.exec();
		container.setContainerid(createContainerResponse.getId());
		System.out.println("Created container:" + container.getContainerid());

		return createContainerResponse;
	}

	/**
	 * 
	 * @param computeMachine
	 * @param container
	 * @param containerDependency
	 * @return
	 * @throws DockerException
	 */
	public Map<DockerClient, CreateContainerResponse> createContainer(Compute computeMachine, Container container,
			Multimap<String, String> containerDependency) throws DockerException {
		preCheckDockerClient(computeMachine);
		CreateContainerCmd createContainer = containerBuilder(container, containerDependency);

		CreateContainerResponse createContainerResponse = createContainer.exec();
		container.setContainerid(createContainerResponse.getId());
		System.out.println("Created container: " + container.getContainerid());

		Map<DockerClient, CreateContainerResponse> result = new LinkedHashMap<DockerClient, CreateContainerResponse>();
		result.put(dockerClient, createContainerResponse);

		return result;
	}

	/**
	 * 
	 * @param container
	 * @param containerDependency
	 *            may be null if no dependencies.
	 * @return
	 * @throws DockerException
	 */
	public CreateContainerCmd containerBuilder(Container container, Multimap<String, String> containerDependency)
			throws DockerException {
		CreateContainerCmd createContainer = null;

		if (container.getImage() == null || container.getImage().trim().isEmpty()) {
			createContainer = this.dockerClient.createContainerCmd(DEFAULT_IMAGE_NAME);
		} else {
			createContainer = this.dockerClient.createContainerCmd(container.getImage().trim());
		}

		String command = container.getCommand(); // internal command to execute on creation.
		if (command != null && !command.trim().isEmpty()) {
			// String[] commands = (StringUtils.deleteWhitespace(command)).split(",");

			String[] commands = getCmdArray(command);
			createContainer.withCmd(commands);
			// createContainer.withCmd("/bin/sh", "-c", "httpd -p 8000 -h /www; tail -f
			// /dev/null");
		} else if (!StringUtils.isNotBlank(container.getImage())) { // else overrides image command if any (often)
			createContainer.withCmd("sleep", "9999");
		}

		if (container.getCpuShares() > 0) {
			createContainer.withCpuShares(container.getCpuShares());
		}

		// Add hostname container.
		if (container.getOcciComputeHostname() != null && !container.getOcciComputeHostname().trim().isEmpty()) {
			createContainer.withHostName(StringUtils.deleteWhitespace(container.getOcciComputeHostname()));
		}

		// Add hostnames to /etc/hosts in the container.

		// ArrayOfString addHosts = container.getAddHost();

		if (container.getAddHost() != null && !container.getAddHost().trim().isEmpty()) {

			createContainer.withExtraHosts(container.getAddHost().split(";"));
			// createContainer.withExtraHosts(addHosts.getValues());
		}

		if (StringUtils.isNotBlank(container.getCpuSetCpus())) {
			createContainer.withCpusetCpus(container.getCpuSetCpus());
		}

		if (StringUtils.isNotBlank(container.getCpuSetMems())) {
			createContainer.withCpusetCpus(container.getCpuSetMems());
		}
		if (container.isPrivileged()) {
			createContainer.withPrivileged(container.isPrivileged());
		}

		if (container.getDns() != null && !container.getDns().trim().isEmpty()) {
			createContainer.withDns(container.getDns().split(";"));
		}

		// if (container.getDns() != null && !container.getDns().getValues().isEmpty())
		// {
		// createContainer.withDns(container.getDns().getValues());
		// }

		if (container.getEnvironment() != null && !container.getEnvironment().trim().isEmpty()) {
			createContainer.withEnv(container.getEnvironment().split(";"));
		}

		// if (container.getEnvironment() != null &&
		// !container.getEnvironment().getValues().isEmpty()) {
		// createContainer.withEnv(container.getEnvironment().getValues());
		// }

		// Define exposed port and port binding access.

		// ArrayOfString ports = container.getPorts();
		List<String> ports = new ArrayList<>();
		// Get the original tab.
		String portsValues = container.getPorts();
		if (portsValues != null && !portsValues.trim().isEmpty()) {
			// example: 8080:80;4043:443 etc. ; is the separator for tab and : port
			// separator.
			String[] portsTab = container.getPorts().split(";");
			// Build the ports list.
			for (String port : portsTab) {
				ports.add(port); // 8080:80..
			}
		}

		if (ports != null && !ports.isEmpty()) {
			System.out.println("Container ports : ");
			List<ExposedPort> exposedPorts = new LinkedList<>();
			List<PortBinding> portBindings = new LinkedList<>();

			for (String port : ports) {
				System.out.println("port: " + port);
				String[] lrports = port.split(":"); // ex: 2000:80
				if (lrports[0].contains("/tcp")) {
					lrports[0] = lrports[0].replace("/tcp", "");
				}
				ExposedPort tcp = ExposedPort.tcp(Integer.parseInt(lrports[0]));
				PortBinding portBinding = null;
				// Exposed port is set with lrPorts[0]
				// Binding port is set with lrPorts[1]
				if (lrports.length == 2) {
					if (StringUtils.isNotBlank(lrports[1])) {
						portBinding = new PortBinding(Binding.bindPort(Integer.parseInt(lrports[1])), tcp);
					} else {
						portBinding = new PortBinding(Binding.bindPort(32768), tcp); // TODO Create dynamic port number
					}
					portBindings.add(portBinding);
				}
				exposedPorts.add(tcp);
			}
			if (!exposedPorts.isEmpty()) {
				createContainer.withExposedPorts(exposedPorts);
			}
			if (!portBindings.isEmpty()) {
				createContainer.withPortBindings(portBindings);
			}
		} else {
			LOGGER.warn("No exposed nor binding ports defined for the container : " + container.getName());
		}
		if (StringUtils.isNotBlank(container.getName())) {
			createContainer.withName(StringUtils.deleteWhitespace(container.getName()));
		}
		if (StringUtils.isNotBlank(container.getNet())) {
			createContainer.withNetworkMode(StringUtils.deleteWhitespace(container.getNet()));
		}
		if (container.isPublishAll()) {
			createContainer.withPublishAllPorts(container.isPublishAll());
		}
		if (container.isStdinOpen()) {
			createContainer.withStdinOpen(container.isStdinOpen());
		}
		if (StringUtils.isNotBlank(container.getUser())) {
			createContainer.withUser(container.getUser());
		}

		String volumesStr = container.getVolumes();
		if (volumesStr != null && !volumesStr.trim().isEmpty()) {
			String[] volumes = volumesStr.split(";");
			List<Volume> vs = new LinkedList<Volume>();
			for (String volumeName : volumes) {
				Volume newVolume = new Volume(volumeName);
				vs.add(newVolume);
			}
			createContainer.withVolumes(vs);
		}

		// With ArraysOfString datatype.
		// if (container.getVolumes() != null &&
		// !container.getVolumes().getValues().isEmpty()) {
		// List<Volume> vs = new LinkedList<Volume>();
		// for (String volume : container.getVolumes().getValues()) {
		// Volume newVolume = new Volume(volume);
		// vs.add(newVolume);
		// }
		// createContainer.withVolumes(vs);
		// }

		if (container.getMemLimit() != null && container.getMemLimit() > 0) {
			// TODO : Replace integer by Long in specification model occie.
			createContainer.withMemory(Long.valueOf(container.getMemLimit()));
		}

		if (container.getMemorySwap() != null && container.getMemorySwap() > 0) {
			// TODO : Replace integer by Long in specification model occie.
			createContainer.withMemory(Long.valueOf(container.getMemorySwap()));
		}

		String lxcConfStr = container.getLxcConf();
		if (lxcConfStr != null && !lxcConfStr.trim().isEmpty()) {
			List<LxcConf> lxcConfigs = new LinkedList<>();
			// Example : lxc.aa_profile:unconfined etc.
			String[] lxcConfs = lxcConfStr.split(";");
			for (String lxcConf : lxcConfs) {
				String[] lxcKeyVal = lxcConf.split(":");
				if (lxcConf.length() == 2) {
					LxcConf lxcCon = new LxcConf(lxcKeyVal[0], lxcKeyVal[1]);
					lxcConfigs.add(lxcCon);
				} else {
					throw new DockerException(
							"Lxc conf format must be like this one --> lxc.aa_profile:unconfined --> key:value");
				}
			}
			if (!lxcConfigs.isEmpty()) {
				createContainer.withLxcConf(lxcConfigs);
			}
		}

		// With ArraysOfString datatype.
		// if (container.getLxcConf() != null &&
		// !container.getLxcConf().getValues().isEmpty()) {
		// List<LxcConf> lxcConfigs = new LinkedList<>();
		// // Example : lxc.aa_profile:unconfined etc.
		// for (String lxcConf : container.getLxcConf().getValues()) {
		// String[] lxcKeyVal = lxcConf.split(":");
		// if (lxcConf.length() == 2) {
		// LxcConf lxcCon = new LxcConf(lxcKeyVal[0], lxcKeyVal[1]);
		// lxcConfigs.add(lxcCon);
		// } else {
		// throw new DockerException(
		// "Lxc conf format must be like this one --> lxc.aa_profile:unconfined -->
		// key:value");
		// }
		// }
		// if (!lxcConfigs.isEmpty()) {
		// createContainer.withLxcConf(lxcConfigs);
		// }
		// }

		if (StringUtils.isNotBlank(container.getDomainName())) {
			createContainer.withDomainName(container.getDomainName());
		}

		if (container.getDnsSearch() != null && !container.getDnsSearch().trim().isEmpty()) {
			createContainer.withDnsSearch(container.getDnsSearch().split(";"));
		}

		// with ArraysOfString datatype.
		// if (container.getDnsSearch() != null &&
		// !container.getDnsSearch().getValues().isEmpty()) {
		// createContainer.withDnsSearch(container.getDnsSearch().getValues());
		// }

		if (StringUtils.isNotBlank(container.getEntrypoint())) {
			// TODO : Convert to ArrayOfString in model occie.
			String[] entrypoint = container.getEntrypoint().split(",");
			createContainer.withEntrypoint(entrypoint);
		}

		if (StringUtils.isNotBlank(container.getPid())) {
			createContainer.withPidMode(StringUtils.deleteWhitespace(container.getPid()));
		}

		if (container.isReadOnly()) {
			createContainer.withReadonlyRootfs(container.isReadOnly());
		}

		if (container.isTty()) {
			createContainer.withTty(container.isTty());
		}

		if (StringUtils.isNotBlank(container.getRestart())) {
			createContainer
					.withRestartPolicy(RestartPolicy.parse(StringUtils.deleteWhitespace(container.getRestart())));
		}

		if (StringUtils.isNotBlank(container.getWorkingDir())) {
			createContainer.withWorkingDir(StringUtils.deleteWhitespace(container.getWorkingDir()));
			// createContainer.getCpusetCpus();
		}

		List<Container> containersWithVolumes = new LinkedList<>();

		List<org.eclipse.cmf.occi.docker.Volume> volumesInsideHost = new LinkedList<>();

		for (Resource r : containersWithVolumes(container)) {
			if (r instanceof Container) {
				containersWithVolumes.add((Container) r);
			}
			if (r instanceof org.eclipse.cmf.occi.docker.Volume) {
				volumesInsideHost.add((org.eclipse.cmf.occi.docker.Volume) r);
			}
		}

		if (!containersWithVolumes.isEmpty()) {
			List<VolumesFrom> volumesFrom = new LinkedList<>();
			for (Container c : containersWithVolumes) {
				volumesFrom.add(new VolumesFrom(c.getName()));
				System.out.println(c.getName());
			}
			createContainer.withVolumesFrom(volumesFrom);
		}

		if (!volumesInsideHost.isEmpty()) {
			List<Bind> volumesBind = new LinkedList<>();
			List<Volume> vs = new ArrayList<>();
			for (org.eclipse.cmf.occi.docker.Volume v : volumesInsideHost) {
				Volume newVolume = null;
				if (!StringUtils.isBlank(v.getDestination())) {
					newVolume = new Volume(v.getDestination());
					vs.add(newVolume);
				}

				if (!StringUtils.isBlank(v.getSource())) {
					Bind newBind = new Bind(v.getSource(), newVolume);
					volumesBind.add(newBind);
				}
			}
			createContainer.withVolumes(vs);
			createContainer.withBinds(volumesBind);
		}

		// Define container network links if any.
		if (containerDependency != null && containerDependency.containsKey(container.getName())) {
			List<String> depdupeContainers = new ArrayList<String>(
					new LinkedHashSet<String>(containerDependency.get(container.getName())));

			List<Link> dockeClientlinks = new ArrayList<>();
			Link dockeClientlink = null;
			for (String entry : depdupeContainers) {
				dockeClientlink = new Link(entry, container.getName() + "LinkTo" + entry);
				dockeClientlinks.add(dockeClientlink);
			}
			if (depdupeContainers.size() > 1) {
				createContainer.withLinks(dockeClientlinks);
			} else if (depdupeContainers.size() == 1) {
				createContainer.withLinks(dockeClientlink);
			}
		}

		return createContainer;
	}

	public String[] getCmdArray(String command) {
		String[] cmdArray;
		if (command != null && !command.isEmpty()) {
			cmdArray = command.split(",");
			// Scan for space before and space end...
			for (int i = 0; i < cmdArray.length; i++) {
				cmdArray[i] = cmdArray[i].trim();
			}
			return cmdArray;
		}

		return new String[0];
	}

	/**
	 * List target volumes resources from a container.
	 * 
	 * @param c
	 *            a model container
	 * @return
	 */
	public List<Resource> containersWithVolumes(Container c) {
		List<Resource> containersFrom = new ArrayList<>();
		for (org.eclipse.cmf.occi.core.Link l : c.getLinks()) {
			if (l instanceof Volumesfrom) {
				containersFrom.add(l.getTarget());
			}
		}
		return containersFrom;
	}

	/**
	 * Control if docker client is set on the good machine.
	 * 
	 * @param computeMachine
	 * @return
	 * @throws DockerException
	 */
	public void preCheckDockerClient(Compute computeMachine) throws DockerException {
		if (this.dockerClient == null) {
			// Build a new Docker client for this machine.
			this.dockerClient = DockerConfigurationHelper.buildDockerClient(computeMachine);
			this.compute = computeMachine;
		}

		if (this.dockerClient == null) {
			// Must never be thrown here.
			throw new DockerException("No docker client found !");
		}
		if (compute != null && compute instanceof Machine && computeMachine instanceof Machine
				&& !(((Machine) compute).getName().equalsIgnoreCase(((Machine) computeMachine).getName()))) {

			this.dockerClient = DockerConfigurationHelper.buildDockerClient(computeMachine);
			this.compute = computeMachine;
		}

	}

	/**
	 * Inspect/describe a container.
	 * 
	 * @param computeMachine
	 * @param containerId
	 * @return
	 * @throws DockerException
	 */
	public InspectContainerResponse inspectContainer(Compute computeMachine, final String containerId)
			throws DockerException {
		preCheckDockerClient(computeMachine);
		if (containerId == null) {
			throw new DockerException("Container id is not set !");
		}

		InspectContainerResponse containerResponse = dockerClient.inspectContainerCmd(containerId).exec();

		return containerResponse;
	}

	/**
	 * Used when container id is unknown or must be refreshed via occiRetrieve().
	 * 
	 * @param computeMachine
	 * @param container
	 * @return
	 * @throws DockerException
	 */
	public InspectContainerResponse inspectContainer(Compute computeMachine, final Container container)
			throws DockerException {
		preCheckDockerClient(computeMachine);
		if (container == null || container.getName() == null) {
			throw new DockerException("Container model or container name is not set !");
		}
		// Search the containerId for this container.
		List<com.github.dockerjava.api.model.Container> containers = listContainer(computeMachine);
		String inspectName;
		String containerId = null;
		for (com.github.dockerjava.api.model.Container con : containers) {
			if (con.getNames() != null && con.getNames().length > 0 && con.getNames()[0] != null) {
				inspectName = con.getNames()[0];
				inspectName = inspectName.replaceAll("/", "");
				if (inspectName.equalsIgnoreCase(container.getName())) {
					containerId = con.getId();
					break;
				}
			}
		}
		if (containerId == null) {
			LOGGER.warn("No id defined for this container, cannot retrieve its informations.");
		} else {
			InspectContainerResponse containerResponse = this.inspectContainer(computeMachine, containerId);
			return containerResponse;
		}

		return null;
	}

	/**
	 * 
	 * @param computeMachine
	 * @param network
	 * @return networkId (String)
	 * @throws DockerException
	 */
	public String createNetwork(Compute computeMachine, Network network) throws DockerException {

		// Set dockerClient
		preCheckDockerClient(computeMachine);

		List<Config> ipamConfigs = new ArrayList<>();
		com.github.dockerjava.api.model.Network.Ipam ipam = null;

		if (StringUtils.isNotBlank(network.getSubnet())) {
			ipamConfigs.add(new com.github.dockerjava.api.model.Network.Ipam.Config().withSubnet(network.getSubnet()));
		} else {
			// TODO : Add default value : 10.67.79.0/24 on specification (model occie) +
			// documentation of what a subnet means.
			ipamConfigs.add(new com.github.dockerjava.api.model.Network.Ipam.Config().withSubnet("10.67.79.0/24"));
		}

		if (StringUtils.isNotBlank(network.getGateway())) {
			ipamConfigs
					.add(new com.github.dockerjava.api.model.Network.Ipam.Config().withGateway(network.getGateway()));
		}
		if (StringUtils.isNotBlank(network.getIpRange())) {
			ipamConfigs
					.add(new com.github.dockerjava.api.model.Network.Ipam.Config().withIpRange(network.getIpRange()));
		}

		try {
			// TODO : Check this getIpam() reference address.
			ipam = new com.github.dockerjava.api.model.Network().getIpam().withConfig(ipamConfigs);

		} catch (Exception e) {
			LOGGER.error("Exception:" + e.getMessage());
			throw new DockerException(e.getMessage(), e);
		}

		// Initiate the NetworkCommand
		CreateNetworkCmd createNetworkCmd = dockerClient.createNetworkCmd().withIpam(ipam);
		if (StringUtils.isNotBlank(network.getName())) {
			createNetworkCmd = createNetworkCmd.withName(network.getName());
		}
		if (StringUtils.isNotBlank(network.getDriver())) {
			createNetworkCmd = createNetworkCmd.withDriver(network.getDriver());
		}

		CreateNetworkResponse createNetworkResponse = null;
		com.github.dockerjava.api.model.Network updateNetwork = null;
		try {
			// Create an overlay network
			createNetworkResponse = createNetworkCmd.withCheckDuplicate(true).exec();
		} catch (InternalServerErrorException exception) {
			LOGGER.error(exception.getMessage());
			createNetworkResponse = null;
			updateNetwork = dockerClient.inspectNetworkCmd().withNetworkId(network.getName()).exec();
			updateNetwork.getId();
		}
		if (createNetworkResponse != null) {
			return createNetworkResponse.getId();
		} else {
			return updateNetwork.getId();
		}
	}

	/**
	 * 
	 * @param computeMachine
	 * @param networks
	 * @throws DockerException
	 */
	public void connectToNetwork(Compute computeMachine, Map<Container, Set<Networklink>> networks)
			throws DockerException {
		// Set dockerClient
		preCheckDockerClient(computeMachine);

		if (networks.size() > 0) {
			for (Map.Entry<Container, Set<Networklink>> entry : networks.entrySet()) {
				for (Networklink netLink : entry.getValue()) {
					try {
						dockerClient.connectToNetworkCmd().withNetworkId(((Network) netLink.getTarget()).getNetworkId())
								.withContainerId(entry.getKey().getContainerid()).exec();
					} catch (InternalServerErrorException exception) {
						LOGGER.error("InternalServerErrorException: " + exception.getMessage());
						throw new DockerException(exception);
					}
				}
			}
		}
	}

	/**
	 * 
	 * @param computeMachine
	 * @param containerId
	 */
	public void removeContainer(Compute computeMachine, String containerId) throws DockerException {
		preCheckDockerClient(computeMachine);
		// TODO : Check response !!!
		dockerClient.removeContainerCmd(containerId).exec();
	}

	public void killContainer(Compute computeMachine, String containerId) throws DockerException {
		preCheckDockerClient(computeMachine);
		dockerClient.killContainerCmd(containerId);
	}

	/**
	 * Start a container.
	 * 
	 * @param computeMachine
	 * @param container
	 * @throws DockerException
	 */
	public void startContainer(Compute computeMachine, Container container, StatsCallBack statsCallBack) throws DockerException {
		preCheckDockerClient(computeMachine);
		try {
			dockerClient.startContainerCmd(container.getContainerid()).exec();

			if (container.isMonitored()) { // Allow the monitoring of a container.
				// Collect monitoring data
				System.out.println("Starting metrics collection");

				// Load new docker client to fix blocking thread problem
				this.dockerClient = DockerConfigurationHelper.buildDockerClient(computeMachine);
				if (statsCallBack != null) {
					System.out.println("Launch docker stats command for container : " + container.getName());
					dockerClient.statsCmd(container.getContainerid()).exec(statsCallBack);
				} 
			}
		} catch (Exception ex) {
			ex.printStackTrace();
			throw new DockerException(ex.getMessage(), ex);
		}
	}

	/**
	 * Stop a container.
	 * 
	 * @param computeMachine
	 * @param container
	 * @throws DockerException
	 */
	public void stopContainer(Compute computeMachine, Container container) throws DockerException {
		preCheckDockerClient(computeMachine);
		if (container.isMonitored()) {
			System.out.println("Stopping monitoring container : " + container.getName());
			// Stop the statscallbacks and recreate a new one.
			try {
				((ContainerConnector)container).getStatsCallBack().close();
				((ContainerConnector)container).setStatsCallBack(new StatsCallBack(container));
			} catch (IOException ex) {
				ex.printStackTrace();
			}
		}
		System.out.println("Effectively stop the container : " + container.getName());
		dockerClient.stopContainerCmd(container.getContainerid()).exec();
		
	}

	/**
	 * Suspend a container (wait container).
	 * 
	 * @param computeMachine
	 * @param container
	 * @throws DockerException
	 */
	public void suspendContainer(Compute computeMachine, Container container) throws DockerException {
		preCheckDockerClient(computeMachine);
		dockerClient.waitContainerCmd(container.getContainerid()).exec(new WaitContainerResultCallback())
				.awaitStatusCode();
	}

	/**
	 * rename a container.
	 * 
	 * @param computeMachine
	 * @param container
	 * @throws DockerException
	 */
	public void renameContainer(Compute computeMachine, Container container, String name) throws DockerException {
		preCheckDockerClient(computeMachine);
		dockerClient.renameContainerCmd(container.getContainerid()).withName(name).exec();
	}

	/**
	 * Delete a container from a compute machine.
	 * 
	 * @param computeMachine
	 * @param container
	 * @throws DockerException
	 */
	public void removeContainer(Compute computeMachine, Container container) throws DockerException {
		String machineName;
		preCheckDockerClient(computeMachine);

		if (computeMachine instanceof Machine) {
			machineName = ((Machine) computeMachine).getName();
		} else {
			// TODO : other computes from different extensions (based on infrastructure and
			// extended like this docker extension).
			// For now we use title from the entity.
			machineName = computeMachine.getTitle();
		}

		if (machineName == null || machineName.trim().isEmpty()) {
			throw new DockerException("Cannot remove a container without compute machine name");
		}

		this.dockerClient.removeContainerCmd(container.getContainerid()).exec();
	}

	/**
	 * List containers from a machine with machine name, empty list if none is
	 * found.
	 * 
	 * @param machineName
	 * @return
	 * @throws DockerException
	 */
	public List<com.github.dockerjava.api.model.Container> listContainer(Compute computeMachine)
			throws DockerException {
		preCheckDockerClient(computeMachine);
		List<com.github.dockerjava.api.model.Container> containers = null;
		try {
			containers = dockerClient.listContainersCmd().withShowAll(true).exec();
		} catch (Exception ex) {
			ex.printStackTrace();
			throw new DockerException(ex.getMessage(), ex);
		}
		if (containers == null) {
			containers = new LinkedList<>();
		}
		return containers;
	}

	/**
	 * Pull an image on a compute machine.
	 * 
	 * @param computeMachine
	 * @param image
	 * @return
	 * @throws DockerException
	 */
	public DockerClient pullImage(Compute computeMachine, String image) throws DockerException {
		preCheckDockerClient(computeMachine);

		String containerImage = image;
		if (!StringUtils.isNotBlank(containerImage)) {
			containerImage = "busybox";
			System.out.println("Use the default Docker Image: " + containerImage);
		}
		System.out.println("Downloading image: ->" + containerImage);
		// Download a pre-built image
		try {
			// If the given image tag doesn't contain a version number, add "latest" as tag
			if (containerImage.indexOf(':') < 0) {
				dockerClient.pullImageCmd(containerImage).withTag("latest").exec(new PullImageResultCallback())
						.awaitSuccess();
			} else {
				dockerClient.pullImageCmd(containerImage).exec(new PullImageResultCallback()).awaitSuccess();
			}
		} catch (Exception e) {
			LOGGER.error(e.getMessage());
			throw new DockerException(e.getMessage(), e);
		}
		System.out.println("Download is finished");
		return this.dockerClient;
	}

	/**
	 * 
	 * @param machine
	 * @param image
	 * @return
	 */
	public boolean machineContainsImage(final String machine, final String image) {
		if (images.get(machine) != null) {
			return images.get(machine).contains(image);
		}
		return false;
	}

	/**
	 * 
	 * @param machine
	 * @param image
	 */
	public void addImageToMachine(final String machine, final String image) {
		List<String> tempList;
		if (!images.containsKey(machine)) {
			tempList = new ArrayList<>();
			tempList.add(image);
			images.put(machine, tempList);
		} else {
			tempList = images.get(machine);
			tempList.add(image);
			images.put(machine, tempList);
		}
	}

	public void connect(String host, String privateKey, String command) throws DockerException {
		Session session = null;
		String user = "docker";
		String tempDir = createTempDir("knowHosts").getAbsolutePath();
		File test = new File(tempDir + "/hosts");

		// Do not remove an existing file
		if (!test.exists()) {
			try {
				test.createNewFile();
			} catch (IOException ex) {
				throw new DockerException(ex);
			}
		}

		try {
			JSch jsc = new JSch();

			jsc.setKnownHosts("/dev/null");
			Properties config = new Properties();
			// TODO : Support host key checking... with ssh connection.
			config.put("StrictHostKeyChecking", "no");
			jsc.addIdentity(privateKey);
			System.out.println("Identity added ..");

			String exCommand = "sudo sh -c " + "\"" + command + "\"";
			System.out.println(exCommand);

			// TODO : Support ssh connection on other ports than 22.
			session = jsc.getSession(user, host, 22);
			System.out.println("Session created ..");
			session.setConfig(config);
			System.out.println("Session config ..");

			session.connect();
			System.out.println("Session connected ..");

			Channel channel = session.openChannel("exec");
			((ChannelExec) channel).setCommand(exCommand);
			((ChannelExec) channel).setErrStream(System.err);
			channel.connect();
		} catch (JSchException e) {
			System.out.println(e.getMessage());

		}
		session.disconnect();
	}

	/**
	 * 
	 * @param key
	 * @param ip
	 * @param knowHosts
	 * @throws DockerException
	 */
	public void addHost(String key, String ip, String knowHosts) throws DockerException {
		try {
			FileWriter tmpwriter = new FileWriter(knowHosts, true);
			String newLine = ip + " ssh-rsa " + key + "\n";
			if (!hostAlreadyExist(newLine, knowHosts)) {
				tmpwriter.append(newLine);
				System.out.println(ip + " ssh-rsa " + key);

				tmpwriter.flush();
				tmpwriter.close();
			}

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 
	 * @param newLine
	 * @param knowHosts
	 * @return
	 * @throws DockerException
	 */
	public boolean hostAlreadyExist(String newLine, String knowHosts) throws DockerException {
		try {
			File hostFile = new File(knowHosts);
			BufferedReader br = new BufferedReader(new FileReader(hostFile));
			String line = null;
			while ((line = br.readLine()) != null) {
				if (line.trim().equalsIgnoreCase(newLine.trim())) {
					return true;
				}
			}
			br.close();
			return false;
		} catch (IOException ex) {
			throw new DockerException(ex);
		}
	}

	/**
	 * 
	 * @param baseName
	 * @return
	 */
	public File createTempDir(String baseName) throws DockerException {
		File baseDir = new File(System.getProperty("java.io.tmpdir"));
		File tempDir = new File(baseDir, baseName);
		if (!tempDir.exists()) {
			if (tempDir.mkdir()) {
				return tempDir;
			}
		} else {
			return tempDir;
		}
		throw new DockerException("Cannot locate a temp directory.");
	}

	/**
	 * Check if a container name already exist on a compute.
	 * 
	 * @param containerName
	 * @param compute
	 * @return true if container name exist, false if not.
	 */
	public boolean containerNameExists(final String containerName, final Compute compute) throws DockerException {
		List<com.github.dockerjava.api.model.Container> containers = listContainer(compute);
		String nameTmp = "";
		List<String> names;
		String linkName = "LinkTo";
		for (com.github.dockerjava.api.model.Container container : containers) {
			names = Arrays.asList(container.getNames());
			if (names != null) {
				for (String name : names) {
					int index = name.indexOf(linkName);
					if (index == -1) {
						nameTmp = name.replaceAll("/", "");
					} else {
						nameTmp = name.substring(index + linkName.length());
					}
					if (nameTmp.equals(containerName)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * 
	 * @param computeMachine
	 * @param container
	 * @throws DockerException
	 */
	public void retrieveAndUpdateContainerModel(final Compute computeMachine, Container container)
			throws DockerException {
		InspectContainerResponse resp = this.inspectContainer(computeMachine, container);
		if (resp != null) {
			ModelHandler modelHandler = new ModelHandler();
			modelHandler.updateContainerModel(container, resp);
		}
	}

	public ComputeStatus getCurrentContainerStatus(final Compute computeMachine, final Container container)
			throws DockerException {
		InspectContainerResponse resp = this.inspectContainer(computeMachine, container);
		ComputeStatus computeStatus = ComputeStatus.INACTIVE; // Default status.
		if (resp != null) {
			if (resp.getState().getRunning()) {
				computeStatus = ComputeStatus.ACTIVE;
			}
			if (resp.getState().getPaused()) {
				computeStatus = ComputeStatus.SUSPENDED;
			}

		}
		return computeStatus;
	}

}
