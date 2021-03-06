/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package iotconfig

import (
	"context"

	iotv1alpha1 "github.com/enmasseproject/enmasse/pkg/apis/iot/v1alpha1"
	"github.com/enmasseproject/enmasse/pkg/util/install"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
)

func (r *ReconcileIoTConfig) processCollector(ctx context.Context, config *iotv1alpha1.IoTConfig) error {
	return r.processDeployment(ctx, "iot-gc", config, r.reconcileCollectorDeployment)
}

func (r *ReconcileIoTConfig) reconcileCollectorDeployment(config *iotv1alpha1.IoTConfig, deployment *appsv1.Deployment) error {

	install.ApplyDeploymentDefaults(deployment, "iot", "iot-core", "iot-gc")

	deployment.Spec.Replicas = nil

	err := install.ApplyContainerWithError(deployment, "collector", func(container *corev1.Container) error {
		if err := SetContainerImage(container, "iot-gc", MakeImageProperties(config)); err != nil {
			return err
		}

		container.Resources = corev1.ResourceRequirements{
			Limits: corev1.ResourceList{
				corev1.ResourceMemory: *resource.NewQuantity(128*1024*1024 /* 128Mi */, resource.BinarySI),
			},
		}

		return nil
	})

	if err != nil {
		return err
	}

	return nil
}
