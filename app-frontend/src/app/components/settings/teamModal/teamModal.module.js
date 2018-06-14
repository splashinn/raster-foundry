import angular from 'angular';
import teamModalTpl from './teamModal.html';

const TeamModalComponent = {
    templateUrl: teamModalTpl,
    controller: 'TeamModalController',
    bindings: {
        close: '&',
        dismiss: '&',
        modalInstance: '<',
        resolve: '<'
    }
};

class TeamModalController {
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

const TeamModalModule = angular.module('components.settings.teamModal', []);

TeamModalModule.component('rfTeamModal', TeamModalComponent);
TeamModalModule.controller('TeamModalController', TeamModalController);

export default TeamModalModule;
