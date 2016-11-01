package brooklyn.entity.proxy.aws;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.*;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.EntityAsserts;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.core.location.Locations;
import org.apache.brooklyn.core.location.PortRanges;
import org.apache.brooklyn.core.mgmt.internal.LocalManagementContext;
import org.apache.brooklyn.core.test.BrooklynAppLiveTestSupport;
import org.apache.brooklyn.entity.group.DynamicCluster;
import org.apache.brooklyn.entity.software.base.EmptySoftwareProcess;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.webapp.tomcat.Tomcat8Server;
import org.apache.brooklyn.location.jclouds.JcloudsSshMachineLocation;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.http.HttpAsserts;
import org.apache.brooklyn.util.net.Networking;
import org.apache.brooklyn.util.ssh.BashCommands;
import org.apache.brooklyn.util.text.Identifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.AttachInternetGatewayRequest;
import com.amazonaws.services.ec2.model.CreateInternetGatewayResult;
import com.amazonaws.services.ec2.model.CreateSubnetRequest;
import com.amazonaws.services.ec2.model.CreateSubnetResult;
import com.amazonaws.services.ec2.model.CreateVpcRequest;
import com.amazonaws.services.ec2.model.CreateVpcResult;
import com.amazonaws.services.ec2.model.DeleteInternetGatewayRequest;
import com.amazonaws.services.ec2.model.DeleteSubnetRequest;
import com.amazonaws.services.ec2.model.DeleteVpcRequest;
import com.amazonaws.services.ec2.model.DetachInternetGatewayRequest;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;


public class ElbControllerLiveTest extends BrooklynAppLiveTestSupport {

    // TODO Add more tests
    // - other protocols, such as HTTPS, and TCP using `nc`
    // - removing nodes cleanly, to ensure they are removed from the pool
    // - health checker, to ensure  
    
    // Note we need to pass an explicit list of AZs - if it tries to use all of them, then get an error
    // that us-east-1a and us-east-1d are not compatible.
    
    private static final Logger LOG = LoggerFactory.getLogger(ElbControllerLiveTest.class);

    public static final String PROVIDER = "aws-ec2";
    public static final String REGION_NAME = "us-east-1";
    public static final List<String> AVAILABILITY_ZONES = ImmutableList.of("us-east-1b", "us-east-1a");
    public static final String LOCATION_SPEC = PROVIDER + (REGION_NAME == null ? "" : ":" + REGION_NAME);
    public static final String LOCATION_AZ_SPEC = PROVIDER + ":" + AVAILABILITY_ZONES.get(0);
    public static final String TINY_HARDWARE_ID = "t1.micro";
    public static final String SMALL_HARDWARE_ID = "m1.small";
    public static final String CIDR = "10.0.0.9/16";

    private static final URI warUri = URI.create("https://repo1.maven.org/maven2/org/apache/brooklyn/example/brooklyn-example-hello-world-webapp/0.7.0-incubating/brooklyn-example-hello-world-webapp-0.7.0-incubating.war");
    public static final String BROOKLYN_LOCATION_JCLOUDS_AWS_EC2_IDENTITY = "brooklyn.location.jclouds.aws-ec2.identity";
    public static final String BROOKLYN_LOCATION_JCLOUDS_AWS_EC2_CREDENTIAL = "brooklyn.location.jclouds.aws-ec2.credential";

    // Image: {id=us-east-1/ami-7d7bfc14, providerId=ami-7d7bfc14, name=RightImage_CentOS_6.3_x64_v5.8.8.5, location={scope=REGION, id=us-east-1, description=us-east-1, parent=aws-ec2, iso3166Codes=[US-VA]}, os={family=centos, arch=paravirtual, version=6.0, description=rightscale-us-east/RightImage_CentOS_6.3_x64_v5.8.8.5.manifest.xml, is64Bit=true}, description=rightscale-us-east/RightImage_CentOS_6.3_x64_v5.8.8.5.manifest.xml, version=5.8.8.5, status=AVAILABLE[available], loginUser=root, userMetadata={owner=411009282317, rootDeviceType=instance-store, virtualizationType=paravirtual, hypervisor=xen}}
    //runTest(ImmutableMap.of("imageId", "us-east-1/ami-7d7bfc14", "hardwareId", SMALL_HARDWARE_ID));

    protected BrooklynProperties brooklynProperties;
    protected String testVpcId;
    protected String testGatewayId;
    protected List<String> testSubnets = new ArrayList<>();
    protected Location loc;
    protected Location locAZ;
    protected List<Location> locs;

    protected ElbControllerImpl objectUnderTest;

    private ElbController elb;

    private SshMachineLocation localMachine;
    private AmazonEC2Client client;

    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        
        Map<String,?> flags = ImmutableMap.of("tags", ImmutableList.of(getClass().getName()));
        loc = mgmt.getLocationRegistry().resolve(LOCATION_SPEC, flags);
        locAZ = mgmt.getLocationRegistry().resolve(LOCATION_AZ_SPEC, flags);
        locs = ImmutableList.of(loc);
        
        localMachine = mgmt.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("address", Networking.getLocalHost()));
        objectUnderTest = new ElbControllerImpl();
    }
    @BeforeSuite
    public void beforeSuite(){
        // Don't let any defaults from brooklyn.properties (except credentials) interfere with test
        brooklynProperties = BrooklynProperties.Factory.newDefault();
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-description-regex");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-name-regex");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-id");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".inboundPorts");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".hardware-id");

        // Also removes scriptHeader (e.g. if doing `. ~/.bashrc` and `. ~/.profile`, then that can cause "stdin: is not a tty")
        brooklynProperties.remove("brooklyn.ssh.config.scriptHeader");

        mgmt = new LocalManagementContext(brooklynProperties);
        client = createEc2Client();

        createVPC();
        attachInternetGatewayToVPC();
        createSubnet();
    }
    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        try {
            if (elb != null) {
                elb.deleteLoadBalancer();
            }
            if (app != null) {
                app.stop();
            }
        } catch (Exception e) {
            LOG.error("error deleting/stopping ELB app; continuing with shutdown...", e);
        } finally {
            super.tearDown();
        }
    }
    @AfterSuite
    public void cleanUpAWS(){
        for(int i = 0; i < testSubnets.size(); i++){
            deleteSubnet(testSubnets.remove(i));
        }
        deleteInternetGatway(testGatewayId);
        deleteVPC(testVpcId);
        client.shutdown();
    }
    @Test(groups="Live")
    public void testCreateLoadBalancer() throws Exception {
        elb = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_NAME, "myname-"+System.getProperty("user.name")+"-"+Identifiers.makeRandomId(8))
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.INSTANCE_PORT, 8080));
        
        app.addLocations(locs);
        app.start(ImmutableList.<Location>of());
        
        checkNotNull(elb.getAttribute(ElbController.HOSTNAME));
        EntityAsserts.assertAttributeEqualsEventually(elb, Attributes.SERVICE_UP, true);
        EntityAsserts.assertAttributeEqualsEventually(elb, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
    }
    @Test(groups="Live")
    public void testCreateLoadBalancerWithSubnets() throws Exception {

        elb = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_NAME, "myname-"+System.getProperty("user.name")+"-"+Identifiers.makeRandomId(8))
                .configure(ElbController.LOAD_BALANCER_SUBNETS, testSubnets)
                .configure(ElbController.INSTANCE_PORT, 8080));

        app.addLocations(locs);
        app.start(ImmutableList.<Location>of());

        checkNotNull(elb.getAttribute(ElbController.HOSTNAME));
        EntityAsserts.assertAttributeEqualsEventually(elb, Attributes.SERVICE_UP, true);
        EntityAsserts.assertAttributeEqualsEventually(elb, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);

    }
    
    @Test(groups="Live")
    public void testRebindLoadBalancer() throws Exception {
        String elbName = "myname-"+System.getProperty("user.name")+"-"+Identifiers.makeRandomId(8);
        elb = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_NAME, elbName)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES));
        app.start(locs);
        String origHostname = elb.getAttribute(ElbController.HOSTNAME);

        // With no interesting differences in configuration
        ElbController elb2 = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_NAME, elbName)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.BIND_TO_EXISTING, true));
        elb2.start(locs);
        assertEquals(elb2.getAttribute(ElbController.HOSTNAME), origHostname);

        // With lots of differences
        // TODO test scheme, subnets and security groups
        ElbController elb3 = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_NAME, elbName)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.BIND_TO_EXISTING, true)
                .configure(ElbController.HEALTH_CHECK_ENABLED, false)
                .configure(ElbController.INSTANCE_PORT, 1234)
                .configure(ElbController.INSTANCE_PROTOCOL, "TCP")
                .configure(ElbController.LOAD_BALANCER_PORT, 1235)
                .configure(ElbController.LOAD_BALANCER_PROTOCOL, "TCP"));

        elb3.start(locs);
        assertEquals(elb3.getAttribute(ElbController.HOSTNAME), origHostname);
    }
    
    @Test(groups="Live")
    public void testReplaceExistingLoadBalancerForbiddenByDefault() throws Exception {
        String elbName = "myname-"+System.getProperty("user.name")+"-"+Identifiers.makeRandomId(8);
        elb = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_NAME, elbName)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES));
        app.start(locs);
        
        // Cannot replace an existing load balancer unless EXPLICITLY configured to do so
        ElbController elb2 = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_NAME, elbName)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES));
        try {
            elb2.start(locs);
        } catch (Exception e) {
            IllegalStateException unwrapped = Exceptions.getFirstThrowableOfType(e, IllegalStateException.class);
            if (unwrapped == null || !unwrapped.toString().contains("because already exists")) throw e;
        }
    }
    
    @Test(groups="Live")
    public void testReplaceExistingLoadBalancer() throws Exception {
        String elbName = "myname-"+System.getProperty("user.name")+"-"+Identifiers.makeRandomId(8);
        elb = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_NAME, elbName)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.INSTANCE_PORT, 8080));
        app.start(locs);
        String origHostname = elb.getAttribute(ElbController.HOSTNAME);
        
        ElbController elb2 = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_NAME, elbName)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.REPLACE_EXISTING, true));
        elb2.start(locs);

        // TODO Not the best assertion; AWS are allowed to reuse hostnames, but unlikely we'll get exactly the same one back! 
        assertNotEquals(elb2.getAttribute(ElbController.HOSTNAME), origHostname);
    }
    
    @Test(groups="Live")
    public void testLoadBalancerWithHttpTargets() throws Exception {
        DynamicCluster cluster = app.createAndManageChild(EntitySpec.create(DynamicCluster.class)
                .configure(DynamicCluster.INITIAL_SIZE, 1)
                .configure(DynamicCluster.MEMBER_SPEC, EntitySpec.create(Tomcat8Server.class)
                        .configure("war", warUri.toString())
                        .configure(Tomcat8Server.HTTP_PORT, PortRanges.fromInteger(8080))));
        
        elb = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.SERVER_POOL, cluster)
                .configure(ElbController.LOAD_BALANCER_NAME, "myname-"+System.getProperty("user.name")+"-"+Identifiers.makeRandomId(8))
                .configure(ElbController.LOAD_BALANCER_PORT, 80)
                .configure(ElbController.INSTANCE_PORT, 8080));
        
        app.start(ImmutableList.<Location>of(locAZ));
        
        Tomcat8Server appserver = (Tomcat8Server) Iterables.getOnlyElement(cluster.getMembers());
        JcloudsSshMachineLocation machine = (JcloudsSshMachineLocation) Locations.findUniqueMachineLocation(appserver.getLocations()).get();
        
        // double-check that app-server really is reachable (so don't complain about ELB if it's not ELB's fault!)
        String directurl = appserver.getAttribute(Tomcat8Server.ROOT_URL);
        HttpAsserts.assertHttpStatusCodeEventuallyEquals(directurl, 200);
        HttpAsserts.assertContentContainsText(directurl, "Hello");

        Asserts.succeedsEventually(ImmutableMap.of("timeout", 5*60*1000), new Runnable() {
                @Override public void run() {
                    String url = "http://"+elb.getAttribute(ElbController.HOSTNAME)+":80/";
                    HttpAsserts.assertHttpStatusCodeEventuallyEquals(url, 200);
                    HttpAsserts.assertContentContainsText(url, "Hello");
                }});
        
        assertEquals(elb.getAttribute(ElbController.SERVER_POOL_TARGETS), ImmutableMap.of(appserver, machine.getNode().getProviderId()));
        
        cluster.resize(0);
        Asserts.succeedsEventually(new Runnable() {
            @Override public void run() {
                assertEquals(elb.getAttribute(ElbController.SERVER_POOL_TARGETS), ImmutableMap.of());
            }});
    }
    
    // A very fast check that the configuration is all accepted, without error
    @Test(groups={"Live", "Live-sanity"})
    public void testLoadBalancerWithTcpTargetsAndEmptyCluster() throws Exception {
        DynamicCluster cluster = app.createAndManageChild(EntitySpec.create(DynamicCluster.class)
                .configure(DynamicCluster.INITIAL_SIZE, 0));
        
        elb = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.SERVER_POOL, cluster)
                .configure(ElbController.LOAD_BALANCER_NAME, "myname-"+System.getProperty("user.name")+"-"+Identifiers.makeRandomId(8))
                .configure(ElbController.LOAD_BALANCER_PROTOCOL, "TCP")
                .configure(ElbController.INSTANCE_PROTOCOL, "TCP")
                .configure(ElbController.LOAD_BALANCER_PORT, 1234)
                .configure(ElbController.INSTANCE_PORT, 1235)
                .configure(ElbController.HEALTH_CHECK_TARGET, "${instanceProtocol}:${instancePort?c}"));
        
        app.start(ImmutableList.<Location>of(locAZ));
        final String elbHostname = elb.getAttribute(ElbController.HOSTNAME);
        assertNotNull(elbHostname);
    }

    // TODO This is failing currently due to `nc` not being insalled correctly on the centos VM
    // Also, the `nc -l` and `echo "..." | nc hostname port` seem brittle when testing this manually,
    // for whether the command returns properly (hence the `-w 10`) and whether the output file gets
    // populated. Should we use stdout?
    @Test(groups={"Live", "WIP"})
    public void testLoadBalancerWithTcpTargets() throws Exception {
        DynamicCluster cluster = app.createAndManageChild(EntitySpec.create(DynamicCluster.class)
                .configure(DynamicCluster.INITIAL_SIZE, 1)
                .configure(DynamicCluster.MEMBER_SPEC, EntitySpec.create(EmptySoftwareProcess.class)
                        .configure(SoftwareProcess.PROVISIONING_PROPERTIES, ImmutableMap.<String,Object>of(
                                "inboundPorts", ImmutableList.of(22, 1235),
                                "imageId", "us-east-1/ami-7d7bfc14", // centos 6.3
                                "hardwareId", SMALL_HARDWARE_ID))));
        
        elb = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.SERVER_POOL, cluster)
                .configure(ElbController.LOAD_BALANCER_NAME, "myname-"+System.getProperty("user.name")+"-"+Identifiers.makeRandomId(8))
                .configure(ElbController.LOAD_BALANCER_PROTOCOL, "TCP")
                .configure(ElbController.INSTANCE_PROTOCOL, "TCP")
                .configure(ElbController.LOAD_BALANCER_PORT, 1234)
                .configure(ElbController.INSTANCE_PORT, 1235)
                .configure(ElbController.HEALTH_CHECK_TARGET, "${instanceProtocol}:${instancePort?c}"));
        
        app.start(ImmutableList.<Location>of(locAZ));
        final String elbHostname = elb.getAttribute(ElbController.HOSTNAME);

        SoftwareProcess appserver = (SoftwareProcess) Iterables.getOnlyElement(cluster.getMembers());
        JcloudsSshMachineLocation machine = (JcloudsSshMachineLocation) Locations.findUniqueMachineLocation(appserver.getLocations()).get();
        
        String remoteFile = "/tmp/nc.out-"+Identifiers.makeRandomId(8);
        File tempFile = File.createTempFile("test-nc", ".out");

        try {
            // Set up `nc` (and will you that to setup server-side port listener, and to connect to it)
            ImmutableList<String> installNcCommand = ImmutableList.of(BashCommands.installPackage(ImmutableMap.of("apt-get", "netcat", "yum", "nc.x86_64"), "netcat"));
            machine.execScript("install-nc", installNcCommand);
            localMachine.execScript("install-nc", installNcCommand);
            machine.execScript("run-nc-listener", ImmutableList.of("nohup nc -l 1235 > "+remoteFile+" &"));

            // Keep trying to connect to this port through the ELB (will fail until the ELB has been updated)
            Asserts.succeedsEventually(ImmutableMap.of("timeout", 5*60*1000), new Runnable() {
                    @Override public void run() {
                        int returnCode = localMachine.execScript("run-nc-connector", ImmutableList.of("echo \"myexample\" | nc -w 10 "+elbHostname+" 1234"));
                        assertEquals(returnCode, 0);
                    }});
        
            machine.copyFrom(remoteFile, tempFile.getAbsolutePath());
            String transferedData = Joiner.on("\n").join(Files.readLines(tempFile, Charsets.UTF_8));
            
            assertEquals(transferedData, "myexample\n");
            
            assertEquals(elb.getAttribute(ElbController.SERVER_POOL_TARGETS), ImmutableMap.of(appserver, machine.getNode().getProviderId()));

        } finally {
            tempFile.delete();
            machine.execScript("kill-tunnel", Arrays.asList("kill `ps aux | grep ssh | grep \"localhost:24684\" | awk '{print $2}'`"));
        }
    }

    public void createVPC() {
        CreateVpcRequest request = new CreateVpcRequest()
                .withCidrBlock(CIDR);

        CreateVpcResult result = client.createVpc(request);

        if (result != null && result.getVpc() != null) {
            testVpcId = result.getVpc().getVpcId();
            LOG.debug("created a VPC here is the id: {}", testVpcId);
        }
    }
    public void attachInternetGatewayToVPC(){
        CreateInternetGatewayResult gateway = client.createInternetGateway();

        if (gateway != null && gateway.getInternetGateway() != null) {
            testGatewayId = gateway.getInternetGateway().getInternetGatewayId();
            LOG.debug("created internet gateway: {}", testGatewayId);
        }

        AttachInternetGatewayRequest request = new AttachInternetGatewayRequest()
                .withVpcId(testVpcId)
                .withInternetGatewayId(gateway.getInternetGateway().getInternetGatewayId());

        client.attachInternetGateway(request);
        LOG.debug("Attached gateway: {} to vpc: {}", testGatewayId, testVpcId);
    }

    public void deleteVPC(final String vpcId) {
        LOG.debug("Cleaning up VPC:{}",vpcId);
        DeleteVpcRequest request = new DeleteVpcRequest()
                .withVpcId(vpcId);
        client.deleteVpc(request);
    }

    public void createSubnet (){
            CreateSubnetRequest request = new CreateSubnetRequest()
                    .withVpcId(testVpcId)
                    .withCidrBlock(CIDR);

            CreateSubnetResult result = client.createSubnet(request);
            if (result != null && result.getSubnet() != null) {
                testSubnets.add(result.getSubnet().getSubnetId());
                LOG.info("created a subnet:{}",result.getSubnet().getSubnetId());
            }
    }
    public void deleteSubnet (final String subnetId){
        LOG.debug("Cleaning up subnet:{}",subnetId);
        DeleteSubnetRequest request = new DeleteSubnetRequest()
                .withSubnetId(subnetId);
        client.deleteSubnet(request);
    }

    public void deleteInternetGatway(final String gatewayId){
        LOG.debug("Cleaning up Internet Gateway:{}", gatewayId);
        DetachInternetGatewayRequest detachInternetGatewayRequest = new DetachInternetGatewayRequest()
                .withVpcId(testVpcId)
                .withInternetGatewayId(gatewayId);

        client.detachInternetGateway(detachInternetGatewayRequest);
        LOG.debug("Detaching gateway: {} from vpc: {}", testGatewayId, testVpcId);

        DeleteInternetGatewayRequest deleteInternetGatewayRequest = new DeleteInternetGatewayRequest()
                .withInternetGatewayId(gatewayId);
        client.deleteInternetGateway(deleteInternetGatewayRequest);
    }

    public AmazonEC2Client createEc2Client (){
        String identity = mgmt.getBrooklynProperties().get(BROOKLYN_LOCATION_JCLOUDS_AWS_EC2_IDENTITY).toString();
        String credential = mgmt.getBrooklynProperties().get(BROOKLYN_LOCATION_JCLOUDS_AWS_EC2_CREDENTIAL).toString();

        AWSCredentials awsCredentials = new BasicAWSCredentials(identity, credential);
        AmazonEC2Client client = new AmazonEC2Client(awsCredentials);

        Region targetRegion = Region.getRegion(Regions.fromName(REGION_NAME));
        client.setRegion(targetRegion);
        return client;
    }
}
