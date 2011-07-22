/**
 *  Copyright (C) 2010 Cloud.com, Inc.  All rights reserved.
 * 
 * This software is licensed under the GNU General Public License v3 or later.
 * 
 * It is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package com.cloud.network.lb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ejb.Local;
import javax.naming.ConfigurationException;

import org.apache.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.AgentManager.OnError;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.routing.LoadBalancerConfigCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.to.LoadBalancerTO;
import com.cloud.agent.manager.Commands;
import com.cloud.configuration.Config;
import com.cloud.configuration.dao.ConfigurationDao;
import com.cloud.dc.DataCenter;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.network.IPAddressVO;
import com.cloud.network.LoadBalancerVO;
import com.cloud.network.Network;
import com.cloud.network.NetworkManager;
import com.cloud.network.NetworkVO;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.lb.LoadBalancingRule.LbDestination;
import com.cloud.network.router.VirtualNetworkApplianceManager;
import com.cloud.network.router.VirtualRouter;
import com.cloud.network.router.VirtualRouter.Role;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRule.Purpose;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.org.Cluster;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.User;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.component.Inject;
import com.cloud.utils.component.Manager;
import com.cloud.utils.db.DB;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.NicProfile;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VirtualMachineName;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfile.Param;
import com.cloud.vm.dao.DomainRouterDao;

@Local(value = { ElasticLoadBalancerManager.class })
public class ElasticLoadBalancerManagerImpl implements
        ElasticLoadBalancerManager, Manager {
    private static final Logger s_logger = Logger
            .getLogger(ElasticLoadBalancerManagerImpl.class);
    
    @Inject
    IPAddressDao _ipAddressDao;
    @Inject
    AgentManager _agentMgr;
    @Inject
    NetworkManager _networkMgr;
    @Inject
    LoadBalancerDao _loadBalancerDao = null;
    @Inject
    LoadBalancingRulesManager _lbMgr;
    @Inject
    VirtualNetworkApplianceManager _routerMgr;
    @Inject
    DomainRouterDao _routerDao = null;
    @Inject
    protected HostPodDao _podDao = null;
    @Inject
    protected ClusterDao _clusterDao;
    @Inject
    DataCenterDao _dcDao = null;
    @Inject
    protected NetworkDao _networkDao;
    @Inject
    protected NetworkOfferingDao _networkOfferingDao;
    @Inject
    VMTemplateDao _templateDao = null;
    @Inject
    VirtualMachineManager _itMgr;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    ServiceOfferingDao _serviceOfferingDao = null;
    @Inject
    AccountService _accountService;


    String _name;
    String _instance;

    Account _systemAcct;
    ServiceOfferingVO _elasticLbVmOffering;
    
    int _elasticLbVmRamSize;
    int _elasticLbvmCpuMHz;
    



    public long deployLoadBalancerVM(Long networkId, Long accountId) {  /* ELB_TODO :need to remove hardcoded network id,account id,pod id , cluster id */
        NetworkVO network = _networkDao.findById(networkId);

        DataCenter dc = _dcDao.findById(network.getDataCenterId());

        Map<VirtualMachineProfile.Param, Object> params = new HashMap<VirtualMachineProfile.Param, Object>(
                1);
        params.put(VirtualMachineProfile.Param.RestartNetwork, true);
        Account owner = _accountService.getActiveAccount("system", new Long(1));
        DeployDestination dest = new DeployDestination(dc, null, null, null);

        s_logger.debug("About to deploy elastic LB vm if necessary");

        try {
            VirtualRouter elbVm = deployELBVm(network, dest, owner, params);

            s_logger.debug("ELB  vm = " + elbVm);
            if (elbVm == null) {
                throw new InvalidParameterValueException("No VM with id '"
                        + elbVm + "' found.");
            }
            DomainRouterVO elbRouterVm = _routerDao.findById(elbVm.getId());
            String publicIp = elbRouterVm.getGuestIpAddress();
            IPAddressVO ipvo = _ipAddressDao.findByIpAndSourceNetworkId(networkId, publicIp);
            ipvo.setAssociatedWithNetworkId(networkId); 
            _ipAddressDao.update(ipvo.getId(), ipvo);
            return ipvo.getId();
        } catch (Throwable t) {
            String errorMsg = "Error while deploying Loadbalancer VM:  " + t;
            s_logger.warn(errorMsg);
            return 0;
        }

    }
    
    private boolean sendCommandsToRouter(final DomainRouterVO router,
            Commands cmds) throws AgentUnavailableException {
        Answer[] answers = null;
        try {
            answers = _agentMgr.send(router.getHostId(), cmds);
        } catch (OperationTimedoutException e) {
            s_logger.warn("Timed Out", e);
            throw new AgentUnavailableException(
                    "Unable to send commands to virtual router ",
                    router.getHostId(), e);
        }

        if (answers == null) {
            return false;
        }

        if (answers.length != cmds.size()) {
            return false;
        }

        // FIXME: Have to return state for individual command in the future
        if (answers.length > 0) {
            Answer ans = answers[0];
            return ans.getResult();
        }
        return true;
    }

    private void createApplyLoadBalancingRulesCommands(
            List<LoadBalancingRule> rules, DomainRouterVO router, Commands cmds) {

        String elbIp = "";

        LoadBalancerTO[] lbs = new LoadBalancerTO[rules.size()];
        int i = 0;
        for (LoadBalancingRule rule : rules) {
            boolean revoked = (rule.getState()
                    .equals(FirewallRule.State.Revoke));
            String protocol = rule.getProtocol();
            String algorithm = rule.getAlgorithm();

            elbIp = _networkMgr.getIp(rule.getSourceIpAddressId()).getAddress()
                    .addr();
            int srcPort = rule.getSourcePortStart();
            List<LbDestination> destinations = rule.getDestinations();
            LoadBalancerTO lb = new LoadBalancerTO(elbIp, srcPort, protocol,
                    algorithm, revoked, false, destinations);
            lbs[i++] = lb;
        }

        LoadBalancerConfigCommand cmd = new LoadBalancerConfigCommand(lbs);
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_IP,
                router.getPrivateIpAddress());
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_NAME,
                router.getInstanceName());
        cmds.addCommand(cmd);

    }

    protected boolean applyLBRules(DomainRouterVO router,
            List<LoadBalancingRule> rules) throws ResourceUnavailableException {
        Commands cmds = new Commands(OnError.Continue);
        createApplyLoadBalancingRulesCommands(rules, router, cmds);
        // Send commands to router
        return sendCommandsToRouter(router, cmds);
    }

    public boolean applyLoadBalancerRules(Network network,
            List<? extends FirewallRule> rules)
            throws ResourceUnavailableException {
        DomainRouterVO router = _routerDao.findByNetwork(network.getId());/* ELB_TODO :may have multiple LB's , need to get the correct LB based on network id and account id */
                                                                          
        if (router == null) {
            s_logger.warn("Unable to apply lb rules, virtual router doesn't exist in the network "
                    + network.getId());
            throw new ResourceUnavailableException("Unable to apply lb rules",
                    DataCenter.class, network.getDataCenterId());
        }

        if (router.getState() == State.Running) {
            if (rules != null && !rules.isEmpty()) {
                if (rules.get(0).getPurpose() == Purpose.LoadBalancing) {
                    // for elastic load balancer we have to resend all lb rules
                    // belonging to same sourceIpAddressId for the network
                    List<LoadBalancerVO> lbs = _loadBalancerDao
                            .listByNetworkId(network.getId());
                    List<LoadBalancingRule> lbRules = new ArrayList<LoadBalancingRule>();
                    for (LoadBalancerVO lb : lbs) {
                        if (lb.getSourceIpAddressId() == rules.get(0)
                                .getSourceIpAddressId()) {
                            List<LbDestination> dstList = _lbMgr
                                    .getExistingDestinations(lb.getId());
                            LoadBalancingRule loadBalancing = new LoadBalancingRule(
                                    lb, dstList);
                            lbRules.add(loadBalancing);
                        }
                    }

                    return applyLBRules(router, lbRules);
                } else {
                    s_logger.warn("Unable to apply rules of purpose: "
                            + rules.get(0).getPurpose());
                    return false;
                }
            } else {
                return true;
            }
        } else if (router.getState() == State.Stopped
                || router.getState() == State.Stopping) {
            s_logger.debug("Router is in "
                    + router.getState()
                    + ", so not sending apply firewall rules commands to the backend");
            return true;
        } else {
            s_logger.warn("Unable to apply firewall rules, virtual router is not in the right state "
                    + router.getState());
            throw new ResourceUnavailableException(
                    "Unable to apply firewall rules, virtual router is not in the right state",
                    VirtualRouter.class, router.getId());
        }
    }

    @Override
    public boolean configure(String name, Map<String, Object> params)
            throws ConfigurationException {
        _name = name;
        final Map<String, String> configs = _configDao.getConfiguration("AgentManager", params);
        _systemAcct = _accountService.getSystemAccount();
        _instance = configs.get("instance.name");
        if (_instance == null) {
            _instance = "VM";
        }
        boolean useLocalStorage = Boolean.parseBoolean(configs.get(Config.SystemVMUseLocalStorage.key()));

        _elasticLbVmRamSize = NumbersUtil.parseInt(configs.get("elastic.lb.vm.ram.size"), DEFAULT_ELB_VM_RAMSIZE);
        _elasticLbvmCpuMHz = NumbersUtil.parseInt(configs.get("elastic.lb.vm.cpu.mhz"), DEFAULT_ELB_VM_CPU_MHZ);
        _elasticLbVmOffering = new ServiceOfferingVO("System Offering For Elastic LB VM", 1, _elasticLbVmRamSize, _elasticLbvmCpuMHz, 0, 0, true, null, useLocalStorage, true, null, true, VirtualMachine.Type.ElasticLoadBalancerVm, true);
        _elasticLbVmOffering.setUniqueName("Cloud.Com-ElasticLBVm");
        _elasticLbVmOffering = _serviceOfferingDao.persistSystemServiceOffering(_elasticLbVmOffering);
        

        return true;
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    public String getName() {
        return _name;
    }

    
    @DB
    public DomainRouterVO deployELBVm(Network guestNetwork, DeployDestination dest, Account owner, Map<Param, Object> params) throws
                ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        long dcId = dest.getDataCenter().getId();

        // lock guest network
        Long guestNetworkId = guestNetwork.getId();
        guestNetwork = _networkDao.acquireInLockTable(guestNetworkId);

        if (guestNetwork == null) {
            throw new ConcurrentOperationException("Unable to acquire network configuration: " + guestNetworkId);
        }

        try {

            NetworkOffering offering = _networkOfferingDao.findByIdIncludingRemoved(guestNetwork.getNetworkOfferingId());
            if (offering.isSystemOnly() || guestNetwork.getIsShared()) {
                owner = _accountService.getSystemAccount();
            }

            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Starting a elastic ip vm for network configurations: " + guestNetwork + " in " + dest);
            }
            assert guestNetwork.getState() == Network.State.Implemented 
                || guestNetwork.getState() == Network.State.Setup 
                || guestNetwork.getState() == Network.State.Implementing 
                : "Network is not yet fully implemented: "+ guestNetwork;

            DataCenterDeployment plan = null;
            DomainRouterVO router = null;
            
            //router = _routerDao.findByNetworkAndPodAndRole(guestNetwork.getId(), podId, Role.LB);   
            plan = new DataCenterDeployment(dcId, null, null, null, null);
            
            // } else {
            // s_logger.debug("Not deploying elastic ip vm");
            // return null;
            // }

            if (router == null) {
                long id = _routerDao.getNextInSequence(Long.class, "id");
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Creating the elastic LB vm " + id);
                }
 
                List<NetworkOfferingVO> offerings = _networkMgr.getSystemAccountNetworkOfferings(NetworkOfferingVO.SystemControlNetwork);
                NetworkOfferingVO controlOffering = offerings.get(0);
                NetworkVO controlConfig = _networkMgr.setupNetwork(_systemAcct, controlOffering, plan, null, null, false, false).get(0);

                List<Pair<NetworkVO, NicProfile>> networks = new ArrayList<Pair<NetworkVO, NicProfile>>(2);
                NicProfile guestNic = new NicProfile();
                networks.add(new Pair<NetworkVO, NicProfile>((NetworkVO) guestNetwork, guestNic));
                networks.add(new Pair<NetworkVO, NicProfile>(controlConfig, null));
                
                VMTemplateVO template = _templateDao.findSystemVMTemplate(dcId);

               
                router = new DomainRouterVO(id, _elasticLbVmOffering.getId(), VirtualMachineName.getRouterName(id, _instance), template.getId(), template.getHypervisorType(), template.getGuestOSId(),
                        owner.getDomainId(), owner.getId(), guestNetwork.getId(), _elasticLbVmOffering.getOfferHA());
                router.setRole(Role.LB);
                router = _itMgr.allocate(router, template, _elasticLbVmOffering, networks, plan, null, owner);
            }

            State state = router.getState();
            if (state != State.Running) {
                router = this.start(router, _accountService.getSystemUser(), _accountService.getSystemAccount(), params);
            }


            return router;
        } finally {
            _networkDao.releaseFromLockTable(guestNetworkId);
        }
    }
    
    private DomainRouterVO start(DomainRouterVO router, User user, Account caller, Map<Param, Object> params) throws StorageUnavailableException, InsufficientCapacityException,
    ConcurrentOperationException, ResourceUnavailableException {
        s_logger.debug("Starting router " + router);
        if (_itMgr.start(router, params, user, caller) != null) {
            return _routerDao.findById(router.getId());
        } else {
            return null;
        }
    }

}