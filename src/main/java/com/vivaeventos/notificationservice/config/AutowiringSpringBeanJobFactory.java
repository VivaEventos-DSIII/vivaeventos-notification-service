package com.vivaeventos.notificationservice.config;

import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;
import org.springframework.stereotype.Component;

/**
 * Permite que Quartz inyecte beans de Spring en los Jobs.
 *
 * Por defecto, Quartz instancia sus Jobs con new ReminderJob() — sin Spring.
 * Esto significa que @Autowired no funciona dentro de los Jobs.
 *
 * Esta configuración soluciona eso: le dice a Quartz que use el
 * AutowireCapableBeanFactory de Spring para crear los Jobs,
 * de forma que todas las inyecciones de dependencias funcionen normalmente.
 *
 * Con esta clase: notificationService se inyecta correctamente → funciona
 */
@Component
public class AutowiringSpringBeanJobFactory extends SpringBeanJobFactory
        implements ApplicationContextAware {

    private AutowireCapableBeanFactory beanFactory;

    @Override
    public void setApplicationContext(ApplicationContext context) {
        // Guardamos el factory para usarlo al crear Jobs
        this.beanFactory = context.getAutowireCapableBeanFactory();
    }

    @Override
    protected Object createJobInstance(TriggerFiredBundle bundle) throws Exception {
        // 1. Crear la instancia del Job con Quartz (como haría normalmente)
        Object job = super.createJobInstance(bundle);
        // 2. Inyectar las dependencias de Spring en esa instancia
        beanFactory.autowireBean(job);
        return job;
    }
}