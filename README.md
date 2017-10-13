Brooklyn support for ELB
------------------------

Gives support for AWS's Elastic Load Balancer.

Note: this will likely be rolled into core https://github.com/brooklyncentral/brooklyn.
However, it currently depends on the 10MB aws-sdk so this dependency has deliberately not 
been added to core brooklyn.


## Build

From the top level of the project:

    mvn clean install

## Install

Start Brooklyn or AMP, then connect using the Karaf client:

    ./bin/client

Add the feature repository and then the ELB feature:

    feature:repo-add mvn:io.cloudsoft.brooklyn.aws.elb/feature/0.8.0-SNAPSHOT/xml/features
    feature:install amp-aws-elb

Check all the bundles are up:

    list| grep Failed


## Example Usage

Deploy an app like that below, which creates an ELB and a cluster of Tomcat servers:

    location: aws-ec2:us-east-1
    services:
    - type: brooklyn.entity.proxy.aws.ElbController
      name: ELB
      brooklyn.config:
        aws.elb.loadBalancerName: my-example-1
        aws.elb.availabilityZones: [us-east-1a, us-east-1b]
        aws.elb.loadBalancerProtocol: HTTP
        aws.elb.instancePort: 8080
        loadbalancer.serverpool: $brooklyn:entity("cluster")
    
    - type: org.apache.brooklyn.entity.group.DynamicCluster
      id: cluster
      name: cluster
      brooklyn.config:
        initialSize: 2
        enableAvailabilityZones: true
        availabilityZoneNames: [us-east-1a, us-east-1b]
        memberSpec:
          $brooklyn:entitySpec:
            type: org.apache.brooklyn.entity.webapp.tomcat.Tomcat8Server
            brooklyn.config:
              # points at maven-central 0.7.0-incubating/brooklyn-example-hello-world-webapp-0.7.0-incubating.war
              wars.root: https://bit.ly/brooklyn-0_7-helloworld-war
              http.port: 8080


## Releases

| Branch  | Version        | Apache Brooklyn version |
| --------|----------------|-------------------------|
| 0.1.x   | 0.1.0-SNAPSHOT | 0.6.0                   |
| 0.2.x   | 0.2.0-SNAPSHOT | 0.7.0-incubating        |
| 0.3.x   | 0.3.0-SNAPSHOT | 0.8.0-incubating        |
| 0.4.x   | 0.4.0-SNAPSHOT | 0.9.0                   |
| 0.5.x   | 0.5.0-SNAPSHOT | 0.10.0                  |
| 0.6.x   | 0.6.0-SNAPSHOT | 0.11.0                  |
| 0.7.x   | 0.7.0-SNAPSHOT | 0.12.0                  |
| master  | 0.8.0-SNAPSHOT | 1.0.0-SNAPSHOT          |


----

© 2013-2017 Cloudsoft Corporation Limited. All rights reserved.

Use of this software is subject to the Cloudsoft EULA, provided in LICENSE.md and at 

http://www.cloudsoftcorp.com/cloudsoft-developer-license

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
