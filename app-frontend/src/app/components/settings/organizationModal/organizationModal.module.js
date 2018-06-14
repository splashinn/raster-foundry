import angular from 'angular';
import organizationModalTpl from './organizationModal.html';

const OrganizationModalComponent = {
    templateUrl: organizationModalTpl,
    controller: 'OrganizationModalController',
    bindings: {
        close: '&',
        dismiss: '&',
        modalInstance: '<',
        resolve: '<'
    }
};

class OrganizationModalController {
    constructor($timeout, $element) {
        this.$timeout = $timeout;
        this.$element = $element;
        this.permissionDenied = this.resolve.permissionDenied;
    }

    $postLink() {
        this.$timeout(() => {
            const el = $(this.$element[0]).find('input').get(0);
            el.focus();
        }, 0);
    }

    onAdd() {
        this.close({$value: {
            name: this.form.name.$modelValue
        }});
    }
}

const OrganizationModalModule = angular.module('components.settings.organizationModal', []);

OrganizationModalModule.component('rfOrganizationModal', OrganizationModalComponent);
OrganizationModalModule.controller('OrganizationModalController', OrganizationModalController);

export default OrganizationModalModule;
