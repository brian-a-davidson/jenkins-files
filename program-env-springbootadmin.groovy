#!/usr/bin/env groovy

pipeline {
  
  agent {
    docker {
      reuseNode true
      image 'nonanom/release-tools:latest'
    }
  }

  environment {
    VAULT_ADDR="https://test-hashivault.company.com"
    VAULT_MBO_PATH="projects/test/program-env"
    VAULT_APP_PATH="projects/test/program-env/springbootadmin"
    VAULT_SKIP_VERIFY="true"
    ROLE_ID=credentials('36da886e-***-44a2-****-2ce25e0f303e')
    SECRET_ID=credentials('36da886e-***-44a2-****-2ce25e0f303e')
    APP_VERSION="${params.VERSION}"
    APP_NAME="springbootadmin"
    NAMESPACE="default"
    IMAGE="image=azure_registry.azurecr.io/test/springbootadmin:${APP_VERSION}"
    TIMEOUT_VALUE="5m"
  }

  stages {
	  
	 stage('Login') {
      steps {
        sh '''
        vault login $(vault write -format=json auth/approle/login role_id=${ROLE_ID} secret_id=${SECRET_ID} | jq -r .auth.client_token)

        CLIENT_ID=$(vault kv get -field=azure_application_id ${VAULT_MBO_PATH}/azure)
        CLIENT_SECRET=$(vault kv get -field=azure_application_secret ${VAULT_MBO_PATH}/azure)
        TENANT_ID=$(vault kv get -field=tenant_id ${VAULT_MBO_PATH}/azure)
        SUBSCRIPTION_ID=$(vault kv get -field=subscription_id ${VAULT_MBO_PATH}/azure)
        RESOURCE_GROUP=$(vault kv get -field=azure_resource_group_name ${VAULT_MBO_PATH}/azure)
        CLUSTER_NAME=$(vault kv get -field=cluster_name ${VAULT_MBO_PATH}/azure)

        az login --output none --service-principal -u ${CLIENT_ID} -p ${CLIENT_SECRET} --tenant ${TENANT_ID}
        az account set --subscription ${SUBSCRIPTION_ID}
        az account show
        az aks get-credentials --overwrite-existing --resource-group ${RESOURCE_GROUP} --name ${CLUSTER_NAME}
        '''
      }
    }
	  
    stage('Update Vault') {
      steps {
        sh '''
        vault login $(vault write -format=json auth/approle/login role_id=${ROLE_ID} secret_id=${SECRET_ID} | jq -r .auth.client_token)
        vault kv patch ${VAULT_APP_PATH} ${IMAGE}
		'''
      }
    }
	
  stage('Deploy release') {
      steps {
        // Clone our Helm charts repo
        git branch: 'master', credentialsId: 'github-credential', url: 'https://github.com/test-company/test.git'

        // Fetch our values.yaml and run 'helm upgrade --install'
        sh '''
        vault kv get -format=json ${VAULT_APP_PATH} | jq -r .data.data >> ${APP_NAME}.yaml
        helm upgrade --namespace ${NAMESPACE} --install ${APP_NAME} stable/${APP_NAME} -f ${APP_NAME}.yaml
        '''
      }
    }

  stage('Deployment status') {
      steps {
        sh '''
        #!/bin/bash
        set +x
        set +e
	TEAMS_WEBHOOK="https://test.webhook.office.com/webhookb2/0d247591-***-****-b7f6-6ccbce4e9ed3@10d6de58-a709-4821-a02c-4c46747e0059/JenkinsCI/e2754df9c6bb462e****5574bd327faa2/834d2a09-***-****-8d2d-2bface541e2a"

	NEW_VERSION=$(kubectl get pods -n ${NAMESPACE} -o=jsonpath="{.items[*].spec.containers[*].image}" -l app=${APP_NAME} | awk -F'[ :]' '{ print $2}') ; 

	for value in $(echo $APP_NAME); do kubectl rollout status deployment/"${value}" -n ${NAMESPACE} --timeout="${TIMEOUT_VALUE}" ; 
	if [[ "$?" -ne 0 ]]; then echo "deployment failed!"; 
	curl -X POST -H 'Content-type: application/json' --data '{"text":"Deployment has Failed for '${value}', job url is '${BUILD_URL}',
	'${APP_NAME}' is on version: '${NEW_VERSION}'"}' ${TEAMS_WEBHOOK}; exit 1; 
	else echo "deployment succeeded" ; 
	curl -X POST -H 'Content-type: application/json' --data '{"text":"Deployment is Successful for '${value}', job url is '${BUILD_URL}', 
	'${APP_NAME}' is now on version: '${NEW_VERSION}' "}' ${TEAMS_WEBHOOK}; fi; done
		'''
      }
    }
  }
  post {
    cleanup {
      deleteDir()
    }
  }
}
