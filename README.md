# EKS cluster and node group setup using eksctl

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=ContainerOnAWS_eks-eksctl&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=ContainerOnAWS_eks-eksctl) [![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=ContainerOnAWS_eks-eksctl&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=ContainerOnAWS_eks-eksctl)

## Architecture

![Architecture](./screenshots/architecture.png?raw=true)

## Prerequisites

* Install eksctl - https://docs.aws.amazon.com/eks/latest/userguide/eksctl.html

* Tags in VPC subnets for subnet discovery by load balancers

| Subnet    | Key                             |  Value      |
|-----------|---------------------------------|-------------|
| Public    | kubernetes.io/role/elb          |   1         |
| Private   | kubernetes.io/role/internal-elb |   1         |

https://aws.amazon.com/premiumsupport/knowledge-center/eks-vpc-subnet-discovery

## Steps

1. Create EKS cluster and nodegroup
2. Install metrics-server
3. Setup Cluster AutoScaler
4. Install AWS Load Balancer Controller

## Step 1: Create EKS cluster and nodegroup

Create cluster and node group YAML files using template files:

```bash
# region: us-east-1
VPC_ID="vpc-123456"
PUBLIC_SUBNET1="subnet-123"
PUBLIC_SUBNET2="subnet-456"
PRIVATE_SUBNET1="subnet-1234"
PRIVATE_SUBNET2="subnet-5678"

sed -e "s|<vpc-id>|${VPC_ID}|g" eks-cluster-template.yaml | sed -e "s|<public-subnet1>|${PUBLIC_SUBNET1}|g" | sed -e "s|<public-subnet2>|${PUBLIC_SUBNET2}|g" | sed -e "s|<private-subnet1>|${PRIVATE_SUBNET1}|g" | sed -e "s|<private-subnet2>|${PRIVATE_SUBNET2}|g" > eks-cluster.yaml
sed -e "s|<vpc-id>|${VPC_ID}|g" eks-cluster-ng-template.yaml | sed -e "s|<public-subnet1>|${PUBLIC_SUBNET1}|g" | sed -e "s|<public-subnet2>|${PUBLIC_SUBNET2}|g" | sed -e "s|<private-subnet1>|${PRIVATE_SUBNET1}|g" | sed -e "s|<private-subnet2>|${PRIVATE_SUBNET2}|g" > eks-cluster-ng.yaml
```

```bash
eksctl create cluster -f eks-cluster.yaml --without-nodegroup
```

```bash
eksctl create nodegroup -f eks-cluster-ng.yaml
```

* [eks-cluster.yaml](./eks-cluster.yaml)
* [eks-cluster-ng.yaml](./eks-cluster-ng.yaml)

It takes around 24 minutes: Cluster 14m, Manged Node Group 10m. GPU instance is not supported in some AZ such `ap-northeast-2b` Zone of Seoul region.

```bash
2022-04-29 13:34:53 [ℹ]  eksctl version 0.90.0
2022-04-29 13:34:53 [ℹ]  using region us-east-1
2022-04-29 13:34:55 [ℹ]  will use version 1.20 for new nodegroup(s) based on control plane version
2022-04-29 13:34:58 [ℹ]  nodegroup "cpu-ng" will use "" [AmazonLinux2/1.20]
2022-04-29 13:34:58 [ℹ]  nodegroup "gpu-ng" will use "" [AmazonLinux2/1.20]
2022-04-29 13:34:58 [ℹ]  2 existing nodegroup(s) (cpu-ng,gpu-ng) will be excluded
2022-04-29 13:34:58 [ℹ]  1 nodegroup (gpu-ng) was included (based on the include/exclude rules)
2022-04-29 13:34:58 [ℹ]  will create a CloudFormation stack for each of 1 managed nodegroups in cluster "eks-cluster-dev"
2022-04-29 13:34:58 [ℹ]  
2 sequential tasks: { fix cluster compatibility, 1 task: { 1 task: { create managed nodegroup "gpu-ng" } } 
}
2022-04-29 13:34:58 [ℹ]  checking cluster stack for missing resources
2022-04-29 13:34:59 [ℹ]  cluster stack has all required resources
2022-04-29 13:34:59 [ℹ]  building managed nodegroup stack "eksctl-eks-cluster-dev-nodegroup-gpu-ng"
2022-04-29 13:34:59 [ℹ]  deploying stack "eksctl-eks-cluster-dev-nodegroup-gpu-ng"
2022-04-29 13:34:59 [ℹ]  waiting for CloudFormation stack "eksctl-eks-cluster-dev-nodegroup-gpu-ng"
2022-04-29 14:02:29 [ℹ]  1 task: { install Nvidia device plugin }
2022-04-29 14:02:31 [ℹ]  replaced "kube-system:DaemonSet.apps/nvidia-device-plugin-daemonset"
2022-04-29 14:02:31 [ℹ]  as you are using the EKS-Optimized Accelerated AMI with a GPU-enabled instance type, the Nvidia Kubernetes device plugin was automatically installed.
        to skip installing it, use --install-nvidia-plugin=false.
2022-04-29 14:02:31 [✔]  created 0 nodegroup(s) in cluster "eks-cluster-dev"
```

---

Set environment variables:

```bash
REGION=$(aws configure get default.region)
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
CLUSTER_NAME=$(kubectl config current-context | cut -d '@' -f2 | cut -d '.' -f1)
echo "REGION: ${REGION}, ACCOUNT_ID: ${ACCOUNT_ID}, CLUSTER_NAME: ${CLUSTER_NAME}"
```

```bash
eksctl utils associate-iam-oidc-provider --region ${REGION} --cluster ${CLUSTER_NAME} --approve
aws eks describe-cluster --name ${CLUSTER_NAME} --query "cluster.identity.oidc.issuer" --output text
```

[optional] Create the iamidentitymapping to access to Kubernetes cluster on AWS WebConsole:

```bash
eksctl create iamidentitymapping --cluster ${CLUSTER_NAME} --arn arn:aws:iam::${ACCOUNT_ID}:role/<role-name> --group system:masters --username admin --region ${REGION}
```

## Step 2: Install metrics-server

```bash
kubectl create namespace monitoring
helm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/
helm upgrade --install metrics-server metrics-server/metrics-server -n monitoring
```

## Step 3: Setup IAM & Cluster AutoScaler

https://docs.aws.amazon.com/ko_kr/eks/latest/userguide/autoscaling.html

```bash
aws iam create-policy \
    --policy-name AmazonEKSClusterAutoscalerPolicy \
    --policy-document file://cluster-autoscaler-policy.json
```

```bash
eksctl create iamserviceaccount \
  --cluster=${CLUSTER_NAME} \
  --namespace=kube-system \
  --name=cluster-autoscaler \
  --attach-policy-arn=arn:aws:iam::${ACCOUNT_ID}:policy/AmazonEKSClusterAutoscalerPolicy \
  --override-existing-serviceaccounts \
  --approve
```

```bash
curl -o cluster-autoscaler-autodiscover-template.yaml https://raw.githubusercontent.com/kubernetes/autoscaler/master/cluster-autoscaler/cloudprovider/aws/examples/cluster-autoscaler-autodiscover.yaml
echo "CLUSTER_NAME: ${CLUSTER_NAME}"
sed -e "s|<YOUR CLUSTER NAME>|${CLUSTER_NAME}|g" cluster-autoscaler-autodiscover-template.yaml > cluster-autoscaler-autodiscover.yaml
kubectl apply -f cluster-autoscaler-autodiscover.yaml
```

```bash
kubectl annotate deployment.apps/cluster-autoscaler cluster-autoscaler.kubernetes.io/safe-to-evict="false" -n kube-system
```

```bash
# logs for cluster-autoscaler
kubectl logs -n kube-system  -f deployment/cluster-autoscaler
```

## Step 4: Install AWS Load Balancer Controller

```bash
curl -o alb_iam_policy.json https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v2.4.0/docs/install/iam_policy.json
aws iam create-policy --policy-name AWSLoadBalancerControllerIAMPolicy --policy-document file://alb_iam_policy.json
```

```bash
eksctl create iamserviceaccount \
    --cluster ${CLUSTER_NAME} \
    --namespace kube-system \
    --name aws-load-balancer-controller \
    --attach-policy-arn arn:aws:iam::${ACCOUNT_ID}:policy/AWSLoadBalancerControllerIAMPolicy \
    --override-existing-serviceaccounts \
    --approve
```

```bash
kubectl apply --validate=false -f https://github.com/jetstack/cert-manager/releases/download/v1.5.4/cert-manager.yaml
curl -Lo v2_4_0_full-template.yaml https://github.com/kubernetes-sigs/aws-load-balancer-controller/releases/download/v2.4.0/v2_4_0_full.yaml
echo "CLUSTER_NAME: ${CLUSTER_NAME}"
sed -e "s|your-cluster-name|${CLUSTER_NAME}|g" v2_4_0_full-template.yaml > v2_4_0_full.yaml
kubectl apply -f v2_4_0_full.yaml
```

```bash
kubectl get deployment -n kube-system aws-load-balancer-controller
```

```bash
# aws-load-balancer-controller logs
kubectl logs -f $(kubectl get po -n kube-system | egrep -o 'aws-load-balancer-controller-[A-Za-z0-9-]+') -n kube-system
```

## Uninstall

```bash
kubectl delete -f cluster-autoscaler-autodiscover.yaml
kubectl delete -f v2_4_0_full.yaml
```

## Reference

* https://docs.aws.amazon.com/ko_kr/eks/latest/userguide/autoscaling.html

* https://github.com/aws/eks-charts/tree/master/stable/aws-load-balancer-controller

## Link

* [https://github.com/ContainerOnAWS/aws-container](https://github.com/ContainerOnAWS/aws-container)
