'use client';

import { AuthenticatedAppPage } from '@/components/auth/AuthenticatedAppPage';
import { DashboardCanvas } from '@/features/dashboard/DashboardCanvas';

export default function DashboardPage() {
    return (
        <AuthenticatedAppPage>
            {(auth) => <DashboardCanvas session={auth.session!} />}
        </AuthenticatedAppPage>
    );
}
